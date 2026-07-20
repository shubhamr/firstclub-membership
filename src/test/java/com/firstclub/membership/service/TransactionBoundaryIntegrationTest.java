package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.exception.PaymentFailedException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.model.PaymentStatus;
import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionStatus;
import com.firstclub.membership.repository.PaymentRepository;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

/**
 * Pins the two transaction-boundary guarantees.
 *
 * <ol>
 *   <li><b>Payment is charged outside the DB transaction (reserve → charge → activate).</b> A
 *       declined charge leaves no live subscription — the PENDING reservation is abandoned — and
 *       the user can subscribe again once the gateway recovers. This is only observable because the
 *       charge is a distinct step between two committed transactions.
 *   <li><b>A read never writes.</b> {@code getMembership} on a past-grace subscription returns the
 *       inactive view without persisting the EXPIRED transition — that belongs to the scheduled
 *       sweep. The row is left untouched for the sweeper to normalize.
 * </ol>
 */
class TransactionBoundaryIntegrationTest extends AbstractIntegrationTest {

  /** Toggles the injected gateway for a single test. */
  static final AtomicBoolean DECLINE = new AtomicBoolean(false);

  /** When set, the gateway fails by throwing, the way the real circuit-breaker fallback does. */
  static final AtomicBoolean THROW = new AtomicBoolean(false);

  @TestConfiguration
  static class ControllableGatewayConfig {
    @Bean
    @Primary
    PaymentPort controllableGateway() {
      return (userId, amount, reference) -> {
        if (THROW.get()) {
          throw new PaymentFailedException("gateway unavailable");
        }
        return DECLINE.get()
            ? new PaymentResult(false, null, "declined")
            : PaymentResult.ok("txn-" + reference);
      };
    }
  }

  @Autowired SubscriptionService subscriptionService;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired PaymentRepository paymentRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void resetGateway() {
    DECLINE.set(false);
    THROW.set(false);
    seedUser(6001L);
    seedUser(6002L);
    seedUser(6003L);
    seedUser(6004L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  @Test
  void declinedCharge_leavesNoLiveSubscription_andUserCanRetry() {
    long userId = 6001L;
    DECLINE.set(true);

    assertThatThrownBy(
            () -> subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId())))
        .isInstanceOf(PaymentFailedException.class);

    // The reservation was rolled forward to CANCELLED, not left dangling: no ACTIVE and no PENDING
    // row remains, so the one-live-subscription unique index is free again.
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
        .isZero();
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.PENDING))
        .isZero();

    // Once the gateway recovers the same user subscribes cleanly: the failed attempt left no trace
    // that would block them.
    DECLINE.set(false);
    var ok = subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    assertThat(ok.status()).isEqualTo("ACTIVE");
  }

  @Test
  void thrownChargeFailure_keepsTheReservation_andTheRetryResumesIt() {
    long userId = 6003L;
    THROW.set(true); // the real adapter fails by throwing, not by returning success=false

    assertThatThrownBy(
            () -> subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId())))
        .isInstanceOf(PaymentFailedException.class);

    // A THROWN failure is ambiguous: the gateway may or may not have captured. The reservation is
    // therefore left PENDING, NOT cancelled. CANCELLED is terminal, and the reference — the only
    // thing that can resolve the ambiguity, because the gateway dedupes on it — is derived from
    // this subscription id. Cancelling would force the retry to mint a new id, a new reference and
    // a new idempotency key, turning a possible capture into a certain double charge.
    var pending =
        subscriptionRepository.findFirstByUserIdAndStatus(userId, SubscriptionStatus.PENDING);
    assertThat(pending).as("an ambiguous charge must not destroy its own reservation").isPresent();
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
        .isZero();
    Long reservedId = pending.orElseThrow().getId();

    // The payment is recorded IN_DOUBT rather than written off, so reconciliation can see it.
    assertThat(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10)))
        .as("the ambiguous charge is recorded IN_DOUBT, not written off")
        .anyMatch(p -> p.getStatus() == PaymentStatus.IN_DOUBT);

    // Once the gateway recovers the user is not blocked: the retry RESUMES the same reservation
    // rather than creating a second one, so it re-asks the gateway with the same reference.
    THROW.set(false);
    var ok = subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    assertThat(ok.status()).isEqualTo("ACTIVE");
    assertThat(ok.id())
        .as("the retry must reuse the reservation, not mint a new one")
        .isEqualTo(reservedId);
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.PENDING))
        .isZero();
  }

  @Test
  void staleReservationReaper_cancelsAnOrphanedPendingRow() {
    long userId = 6004L;
    var plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    var tier = tierRepository.findByCode("SILVER").orElseThrow();
    // A hard crash after reserving but before charge/activate: a PENDING row older than the
    // reservation TTL (default 15m), which the in-line abandon cannot have handled.
    Instant old = Instant.now().minus(Duration.ofMinutes(30));
    subscriptionRepository.save(
        Subscription.pendingReservation(
            userId, plan, tier, plan.getPrice(), old, old.plus(Duration.ofDays(30))));

    int reaped = subscriptionService.sweepStalePendingReservations(50);

    assertThat(reaped).isGreaterThanOrEqualTo(1);
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.PENDING))
        .isZero();
  }

  @Test
  void getMembership_pastGrace_returnsInactive_withoutPersistingExpiry() {
    long userId = 6002L;
    var plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    var tier = tierRepository.findByCode("SILVER").orElseThrow();
    // An ACTIVE row whose paid-through date is well past expiry + grace, written directly so we can
    // exercise the lazy-expiry read path deterministically.
    Instant start = Instant.now().minus(Duration.ofDays(60));
    Instant expired = Instant.now().minus(Duration.ofDays(45));
    subscriptionRepository.save(
        new Subscription(userId, plan, tier, plan.getPrice(), start, expired));

    assertThat(subscriptionService.getMembership(userId).active()).isFalse();

    // The read must not have written: the row is still ACTIVE, awaiting the scheduled sweep. A GET
    // that mutated would have flipped it to EXPIRED and bumped @Version.
    var reloaded = subscriptionRepository.findActiveByUserId(userId).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
  }
}
