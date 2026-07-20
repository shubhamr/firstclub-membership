package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the end-to-end membership lifecycle: subscribe, auto tier bump from activity, downgrade,
 * cancel — with the benefit set tracking the tier at each step.
 */
class MembershipFlowIntegrationTest extends AbstractIntegrationTest {

  @Autowired SubscriptionService subscriptionService;
  @Autowired ActivityIngestionService activityIngestionService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(2001L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long tierId(String code) {
    return tierRepository.findByCode(code).orElseThrow().getId();
  }

  @Test
  void fullLifecycle_subscribeAutoUpgradeDowngradeCancel() {
    long userId = 2001L;

    subscriptionService.subscribe(new SubscribeRequest(userId, planId(), tierId("SILVER")));

    MembershipView silver = subscriptionService.getMembership(userId);
    assertThat(silver.active()).isTrue();
    assertThat(silver.tierCode()).isEqualTo("SILVER");
    assertThat(benefitCodes(silver)).contains("FREE_DELIVERY", "EXTRA_DISCOUNT");

    // This activity clears the PLATINUM criteria (orders >= 15 and spend >= 20000), so ingestion
    // must auto-upgrade the tier without a separate call.
    MembershipView afterActivity =
        activityIngestionService.recordActivity(
            userId,
            new ActivityUpdateRequest(
                20, 990001L, new BigDecimal("25000"), java.time.Instant.now(), List.of("VIP")));
    assertThat(afterActivity.tierCode()).isEqualTo("PLATINUM");
    assertThat(benefitCodes(afterActivity)).contains("PRIORITY_SUPPORT");

    // A member-chosen downgrade must stick even though their activity still qualifies for PLATINUM.
    var subId = afterActivity.subscriptionId();
    subscriptionService.downgrade(subId, tierId("GOLD"));
    assertThat(subscriptionService.getMembership(userId).tierCode()).isEqualTo("GOLD");

    subscriptionService.cancel(subId);
    assertThat(subscriptionService.getMembership(userId).active()).isFalse();
  }

  private List<String> benefitCodes(MembershipView view) {
    return view.benefits().stream().map(b -> b.code()).toList();
  }
}
