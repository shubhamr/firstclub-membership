package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.exception.PaymentFailedException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.repository.CreditNoteRepository;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the economics of the money path: what is credited and what is released must follow what was
 * actually collected.
 *
 * <p>These cover the class of leak the concurrency guards do not touch. Careful arithmetic and
 * race-proof atomic claims still send money the wrong way when a <em>policy input</em> is wrong,
 * and both failures below are reachable with ordinary sequential API calls.
 */
class PaymentEconomicsIntegrationTest extends AbstractIntegrationTest {

  static final AtomicBoolean DECLINE = new AtomicBoolean(false);

  @TestConfiguration
  static class DecliningGatewayConfig {
    @Bean
    @Primary
    PaymentPort decliningGateway() {
      return (userId, amount, reference) ->
          DECLINE.get()
              ? new PaymentResult(false, null, "declined")
              : PaymentResult.ok("txn-" + reference);
    }
  }

  @Autowired SubscriptionService subscriptionService;
  @Autowired CreditNoteRepository creditNoteRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    DECLINE.set(false);
    seedUser(7601L);
    seedUser(7602L);
    seedUser(7604L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long yearlyTrialPlanId() {
    // Yearly is the highest-priced plan and carries the seeded 7-day trial.
    return planRepository.findByActiveTrueOrderByPriceAsc().getLast().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  @Test
  void unconvertedTrialEarnsNoRefundCredit() {
    // Refund credit must be based on what the ledger says was collected, not on pricePaid.
    // pricePaid holds the list price for renewal grandfathering, while a trial that never
    // converted collected nothing. Crediting against pricePaid turns "start a trial, cancel a
    // minute later" into a full-price credit note: real money withdrawn for a zero-value purchase.
    long userId = 7601L;

    SubscriptionDto sub =
        subscriptionService.subscribe(
            new SubscribeRequest(userId, yearlyTrialPlanId(), silverId()));

    // The list price is still locked on the subscription — renewals must bill the real price.
    assertThat(sub.pricePaid()).isGreaterThan(BigDecimal.ZERO);

    subscriptionService.cancel(sub.id());

    assertThat(creditNoteRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10)))
        .as("nothing was collected, so nothing may be credited back")
        .isEmpty();
  }

  @Test
  void paidSubscriptionStillEarnsAProratedCredit() {
    // The collected-amount rule must not suppress refunds for members who did pay.
    long userId = 7602L;
    SubscriptionDto sub =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    subscriptionService.cancel(sub.id());

    assertThat(creditNoteRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10)))
        .as("a member who actually paid keeps their unused-time credit")
        .isNotEmpty();
  }

  @Test
  void memberCanRetryAfterADeclinedCharge() {
    // The charge happens after the reservation transaction commits, so a decline leaves a PENDING
    // row holding the single-active-subscription slot. Unless it is abandoned and resumable, the
    // member is locked out of ever subscribing over a failure that was not theirs.
    long userId = 7604L;

    DECLINE.set(true);
    assertThatThrownBy(
            () -> subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId())))
        .isInstanceOf(PaymentFailedException.class);
    DECLINE.set(false);

    SubscriptionDto retried =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    assertThat(retried.status()).isEqualTo("ACTIVE");
  }
}
