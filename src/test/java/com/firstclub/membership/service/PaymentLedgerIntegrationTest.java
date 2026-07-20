package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.exception.PaymentFailedException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.model.Payment;
import com.firstclub.membership.model.PaymentPurpose;
import com.firstclub.membership.model.PaymentStatus;
import com.firstclub.membership.repository.PaymentRepository;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the payment ledger, the local system of record that makes "we took money and delivered
 * nothing" detectable instead of invisible.
 *
 * <p>Two invariants carry most of the weight:
 *
 * <ul>
 *   <li>the subscribe payment reference is per-<em>attempt</em>, so a resubscribe after cancel is
 *       charged rather than silently replaying the original charge (a free membership);
 *   <li>extending a subscription is a compare-and-set on the previous expiry, so a duplicated
 *       renewal run cannot buy two billing periods with one payment.
 * </ul>
 */
class PaymentLedgerIntegrationTest extends AbstractIntegrationTest {

  static final AtomicBoolean DECLINE = new AtomicBoolean(false);
  static final AtomicBoolean THROW_ON_CHARGE = new AtomicBoolean(false);
  static final AtomicInteger CHARGE_CALLS = new AtomicInteger();
  static final Set<String> CHARGED_REFERENCES = ConcurrentHashMap.newKeySet();

  @TestConfiguration
  static class CountingGatewayConfig {
    @Bean
    @Primary
    PaymentPort countingGateway() {
      return (userId, amount, reference) -> {
        CHARGE_CALLS.incrementAndGet();
        if (THROW_ON_CHARGE.get()) {
          // A real adapter's circuit-breaker fallback fails by THROWING, not by returning
          // success=false — and a thrown failure is ambiguous about whether money moved.
          throw new PaymentFailedException("gateway timeout");
        }
        if (DECLINE.get()) {
          return new PaymentResult(false, null, "declined");
        }
        // Mirrors a real gateway: dedupes on the reference, so a replay costs nothing.
        CHARGED_REFERENCES.add(reference);
        return PaymentResult.ok("txn-" + reference);
      };
    }
  }

  @Autowired SubscriptionService subscriptionService;
  @Autowired RenewalService renewalService;
  @Autowired PaymentLedger paymentLedger;
  @Autowired PaymentRepository paymentRepository;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @BeforeEach
  void reset() {
    DECLINE.set(false);
    THROW_ON_CHARGE.set(false);
    CHARGE_CALLS.set(0);
    CHARGED_REFERENCES.clear();
    seedUser(7301L);
    seedUser(7302L);
    seedUser(7303L);
    seedUser(7304L);
    seedUser(7305L);
    seedUser(7306L);
    seedUser(7307L);
    seedUser(7310L);
    seedUser(7311L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  @Test
  void successfulSubscribe_leavesAnAppliedLedgerRow() {
    long userId = 7301L;
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE);
    assertThat(payments).hasSize(1);
    Payment payment = payments.getFirst();
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPLIED);
    assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.SUBSCRIBE);
    assertThat(payment.getSubscriptionId()).isEqualTo(dto.id());
    assertThat(payment.getAmount()).isEqualByComparingTo(dto.pricePaid());
    assertThat(payment.getGatewayTxnId()).isNotBlank();
  }

  @Test
  void declinedSubscribe_leavesAnAbandonedRow_andNothingStuck() {
    long userId = 7302L;
    DECLINE.set(true);

    assertThatThrownBy(
            () -> subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId())))
        .isInstanceOf(PaymentFailedException.class);

    List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE);
    assertThat(payments).hasSize(1);
    assertThat(payments.getFirst().getStatus()).isEqualTo(PaymentStatus.ABANDONED);

    // ABANDONED means no money moved, so it must never look like an owed delivery.
    assertThat(paymentLedger.findStuckCharged(Instant.now().plusSeconds(60), 100)).isEmpty();
  }

  @Test
  void resubscribeAfterCancel_isChargedAgain_ratherThanReplayingTheOriginalCharge() {
    long userId = 7303L;

    SubscriptionDto first =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    subscriptionService.cancel(first.id());
    SubscriptionDto second =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    // Two DISTINCT references. A reference keyed only on user+plan would collide across the two
    // attempts, so the gateway would replay the first result and the second membership be free.
    List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE);
    assertThat(payments).hasSize(2);
    assertThat(payments).extracting(Payment::getReference).doesNotHaveDuplicates();
    assertThat(payments).allMatch(p -> p.getStatus() == PaymentStatus.APPLIED);
    assertThat(CHARGED_REFERENCES).hasSize(2); // the gateway really was asked to charge twice
    assertThat(payments)
        .extracting(Payment::getSubscriptionId)
        .containsExactlyInAnyOrder(first.id(), second.id());
  }

  @Test
  void ledgerRefusesASecondChargeForAnAlreadyAppliedReference() {
    long userId = 7304L;
    subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    Payment applied = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE).getFirst();

    // This is the barrier that gateway idempotency does not provide: it stops the STATE TRANSITION
    // being replayed, not merely the money.
    IntentOutcome outcome =
        paymentLedger.recordIntent(
            applied.getSubscriptionId(),
            userId,
            applied.getAmount(),
            applied.getReference(),
            PaymentPurpose.SUBSCRIBE);

    assertThat(outcome).isEqualTo(IntentOutcome.ALREADY_DONE);
    assertThat(outcome.shouldCallGateway()).isFalse();
    assertThat(outcome.shouldApply()).isFalse();
  }

  @Test
  void chargedButUnappliedPaymentResumesDeliveryInsteadOfStalling() {
    // recordIntent must distinguish CHARGED from APPLIED. Collapsing them makes a payment that took
    // money and failed to deliver read as "already handled" by every retry, so it can never
    // complete: the member stays charged and undelivered until the subscription expires.
    long userId = 7310L;
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    Payment payment = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE).getFirst();

    // Rewind to the state a crash between charge and apply would leave behind.
    jdbcTemplate.update(
        "update payment set status = 'CHARGED' where reference = ?", payment.getReference());

    IntentOutcome outcome =
        paymentLedger.recordIntent(
            dto.id(),
            userId,
            payment.getAmount(),
            payment.getReference(),
            PaymentPurpose.SUBSCRIBE);

    assertThat(outcome).isEqualTo(IntentOutcome.ALREADY_CHARGED);
    assertThat(outcome.shouldCallGateway()).isFalse(); // never charge the same reference twice
    assertThat(outcome.shouldApply()).isTrue(); // but DO finish the delivery that was paid for
  }

  @Test
  void ambiguousGatewayFailureIsRecordedInDoubtRatherThanAbandoned() {
    // A thrown gateway call does not tell us whether money moved. Recording it as ABANDONED asserts
    // "no capture", and because reconciliation only scans CHARGED, a captured-but-timed-out charge
    // would be written off as a non-event.
    long userId = 7311L;
    THROW_ON_CHARGE.set(true);
    try {
      assertThatThrownBy(
              () ->
                  subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId())))
          .isInstanceOf(RuntimeException.class);
    } finally {
      THROW_ON_CHARGE.set(false);
    }

    Payment payment = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE).getFirst();
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);
    assertThat(paymentLedger.countInDoubt(Instant.now().plusSeconds(60))).isPositive();
  }

  @Test
  void extendIfUnchanged_isANoOpWhenAnotherRunnerAlreadyExtended() {
    long userId = 7305L;
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    Instant originalExpiry =
        subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow().getExpiresAt();
    Instant newExpiry = originalExpiry.plus(Duration.ofDays(30));

    // Each call models one runner's apply phase, so each needs its own transaction — which is also
    // why subscribe() above must happen outside one (reserve → charge → activate asserts that).
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    // First runner wins.
    Integer firstRunner =
        tx.execute(
            s -> subscriptionRepository.extendIfUnchanged(dto.id(), originalExpiry, newExpiry));
    assertThat(firstRunner).isEqualTo(1);

    // Second runner, having planned against the SAME original expiry, affects zero rows. Without
    // this compare-and-set both would extend and one payment would buy two periods — gateway
    // idempotency dedupes the charge but nothing dedupes the extension.
    Integer secondRunner =
        tx.execute(
            s -> subscriptionRepository.extendIfUnchanged(dto.id(), originalExpiry, newExpiry));
    assertThat(secondRunner).isEqualTo(0);

    assertThat(subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow().getExpiresAt())
        .isEqualTo(newExpiry);
  }

  @Test
  void renewal_writesItsOwnLedgerRow_scopedToTheBillingPeriod() {
    long userId = 7306L;
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    renewalService.renew(dto.id());

    List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE);
    assertThat(payments).hasSize(2);
    assertThat(payments)
        .extracting(Payment::getPurpose)
        .containsExactlyInAnyOrder(PaymentPurpose.SUBSCRIBE, PaymentPurpose.RENEWAL);
    assertThat(payments).allMatch(p -> p.getStatus() == PaymentStatus.APPLIED);
  }

  @Test
  void aChargedButUnappliedPayment_isSurfacedByReconciliation() {
    long userId = 7307L;
    subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    Payment applied = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PAGE).getFirst();

    // Simulate the crash window: money moved, the applying transaction never committed.
    Payment stuck =
        paymentRepository.save(
            new Payment(
                applied.getSubscriptionId(),
                userId,
                "crashed-reference-7307",
                new BigDecimal("499.00"),
                PaymentPurpose.UPGRADE,
                Instant.now().minus(Duration.ofHours(1))));
    paymentLedger.markCharged(stuck.getReference(), "txn-crashed");

    List<Payment> owed = paymentLedger.findStuckCharged(Instant.now(), 100);
    assertThat(owed).extracting(Payment::getReference).contains("crashed-reference-7307");
  }

  private static final org.springframework.data.domain.Pageable PAGE =
      org.springframework.data.domain.PageRequest.of(0, 50);
}
