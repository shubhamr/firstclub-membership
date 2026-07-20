package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.exception.IllegalSubscriptionStateException;
import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins that the state machine guards the mutators which change lifecycle fields <em>without</em>
 * changing status: {@code markRenewed}, {@code convertTrial}, {@code markRenewalFailed} and {@code
 * reprice} must all refuse a CANCELLED or EXPIRED subscription.
 *
 * <p>{@code transitionTo} protects status changes only, which leaves these reachable on a dead row:
 * a renewal planned against an ACTIVE subscription can have the expiry sweep flip it to EXPIRED
 * underneath, and the apply phase then extends a dead subscription, leaving a charged member with
 * no access.
 */
class SubscriptionStateGuardIntegrationTest extends AbstractIntegrationTest {

  @Autowired SubscriptionService subscriptionService;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(7501L);
    seedUser(7502L);
    seedUser(7503L);
    seedUser(7504L);
    seedUser(7505L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  private Subscription cancelledSubscription(long userId) {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    subscriptionService.cancel(dto.id());
    return subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow();
  }

  @Test
  void aCancelledSubscriptionCannotBeExtendedByARenewal() {
    Subscription cancelled = cancelledSubscription(7501L);
    Instant expiry = cancelled.getExpiresAt();

    assertThatThrownBy(() -> cancelled.markRenewed(Instant.now().plus(Duration.ofDays(30))))
        .isInstanceOf(IllegalSubscriptionStateException.class);

    assertThat(cancelled.getExpiresAt()).isEqualTo(expiry); // unchanged
  }

  @Test
  void aCancelledSubscriptionCannotBeRepriced() {
    Subscription cancelled = cancelledSubscription(7502L);

    assertThatThrownBy(() -> cancelled.reprice(new BigDecimal("1.00")))
        .isInstanceOf(IllegalSubscriptionStateException.class);
  }

  @Test
  void aCancelledSubscriptionCannotEnterDunning() {
    Subscription cancelled = cancelledSubscription(7503L);

    assertThatThrownBy(() -> cancelled.markRenewalFailed(Instant.now().plus(Duration.ofDays(1))))
        .isInstanceOf(IllegalSubscriptionStateException.class);
  }

  @Test
  void convertingASubscriptionThatIsNotInATrialIsRejected() {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(7504L, planId(), silverId()));
    Subscription active = subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow();
    assertThat(active.getTrialEnd()).isNull();

    assertThatThrownBy(() -> active.convertTrial(Instant.now().plus(Duration.ofDays(365))))
        .isInstanceOf(IllegalSubscriptionStateException.class);
  }

  /**
   * A trial may only be stamped on a reservation. On an active subscription {@code trialEnd} would
   * suppress renewal ({@code findDueForRenewal} filters on {@code trialEnd is null}) and then let
   * {@code convertTrial} overwrite {@code expiresAt}, converting a paid membership into a free one.
   */
  @Test
  void anActiveSubscriptionCannotBeGivenATrial() {
    SubscriptionDto dto =
        subscriptionService.subscribe(new SubscribeRequest(7505L, planId(), silverId()));
    Subscription active = subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow();

    assertThatThrownBy(() -> active.beginTrial(Instant.now().plus(Duration.ofDays(30))))
        .isInstanceOf(IllegalSubscriptionStateException.class);
    assertThat(active.getTrialEnd()).isNull();
  }
}
