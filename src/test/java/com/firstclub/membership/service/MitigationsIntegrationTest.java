package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.repository.CreditNoteRepository;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the commercial surface of a subscription: the price lock, refund, prorated credit notes,
 * trials, and renewal extension.
 */
class MitigationsIntegrationTest extends AbstractIntegrationTest {

  @Autowired SubscriptionService subscriptionService;
  @Autowired RenewalService renewalService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;
  @Autowired CreditNoteRepository creditNoteRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(4001L);
    seedUser(4002L);
    seedUser(4003L);
    seedUser(4004L);
    seedUser(4007L);
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
  void subscribe_locksThePricePaid() {
    var plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(4001L, planId(), silverId()));
    assertThat(dto.pricePaid()).isEqualByComparingTo(plan.getPrice());
  }

  @Test
  void refund_revokesMembership() {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(4002L, planId(), silverId()));
    SubscriptionDto refunded = subscriptionService.refund(dto.id());
    assertThat(refunded.status()).isEqualTo("CANCELLED");
    assertThat(subscriptionService.getMembership(4002L).active()).isFalse();
  }

  @Test
  void cancel_issuesProratedCreditNote() {
    subscriptionService.subscribe(new SubscribeRequest(4003L, planId(), silverId()));
    var subId = subscriptionService.getMembership(4003L).subscriptionId();
    subscriptionService.cancel(subId);

    var credits = subscriptionService.creditNotes(4003L, 0, 50);
    assertThat(credits).hasSize(1);
    assertThat(credits.getFirst().amount()).isGreaterThan(BigDecimal.ZERO);
    assertThat(credits.getFirst().reason()).isEqualTo("CANCEL");
  }

  @Test
  void subscribeToTrialPlan_startsTrialWithNoImmediateCharge() {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(4004L, yearlyTrialPlanId(), silverId()));
    assertThat(dto.trialEnd()).isNotNull();
    // A trial grants full membership access before any money is collected.
    assertThat(subscriptionService.getMembership(4004L).active()).isTrue();
  }

  @Test
  void renew_extendsThePaidThroughDate() {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(4007L, planId(), silverId()));
    var before = subscriptionService.getMembership(4007L).expiresAt();
    renewalService.renew(dto.id());
    var after = subscriptionService.getMembership(4007L).expiresAt();
    assertThat(after).isAfter(before);
  }
}
