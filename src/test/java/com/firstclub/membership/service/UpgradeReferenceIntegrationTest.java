package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins that the upgrade payment reference is unique per <em>attempt</em>, not per (subscription,
 * tier).
 *
 * <p>A reference of the form {@code "upgrade-{subId}-{tierId}"} is permanent for the life of the
 * subscription: once it reaches APPLIED the ledger answers "already done" to every later upgrade to
 * the same tier, forever. A member who upgrades to Gold, downgrades to Silver, then upgrades back
 * then gets HTTP 200 with their un-upgraded tier in the body — no charge, no tier change, no error,
 * and no way to reach that tier again on that subscription.
 *
 * <p>The reference carries a monotonic component so each attempt is its own charge, while staying
 * stable within a single attempt, which is what lets a failed apply resume rather than
 * double-charge.
 */
class UpgradeReferenceIntegrationTest extends AbstractIntegrationTest {

  @Autowired SubscriptionService subscriptionService;
  @Autowired ActivityIngestionService activityIngestionService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(7701L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long tierId(String code) {
    return tierRepository.findByCode(code).orElseThrow().getId();
  }

  @Test
  void memberCanUpgradeAgainAfterDowngradingFromTheSameTier() {
    long userId = 7701L;
    subscriptionService.subscribe(new SubscribeRequest(userId, planId(), tierId("SILVER")));

    // Qualify for the higher tiers.
    activityIngestionService.recordActivity(
        userId,
        new ActivityUpdateRequest(
            20, 990002L, new BigDecimal("25000"), java.time.Instant.now(), List.of("VIP")));

    Long subId = subscriptionService.getMembership(userId).subscriptionId();
    subscriptionService.downgrade(subId, tierId("SILVER"));

    // First paid upgrade to GOLD, which is what writes the "upgrade to gold" ledger row. The
    // auto-upgrade from the activity above does not: reevaluateTier changes the tier directly
    // without a charge, so it never touches the ledger. Omitting this step would leave the test
    // passing under a fixed reference, pinning nothing.
    subscriptionService.upgrade(subId, tierId("GOLD"));
    assertThat(subscriptionService.getMembership(userId).tierCode()).isEqualTo("GOLD");

    subscriptionService.downgrade(subId, tierId("SILVER"));
    assertThat(subscriptionService.getMembership(userId).tierCode()).isEqualTo("SILVER");

    // Second upgrade to the same tier: it must be a distinct charge, not a no-op. Under a fixed
    // "upgrade-{sub}-{tier}" reference the ledger still holds the first attempt as APPLIED, and
    // this call silently returns SILVER.
    subscriptionService.upgrade(subId, tierId("GOLD"));

    assertThat(subscriptionService.getMembership(userId).tierCode())
        .as("re-upgrading to a previously held tier must actually change the tier")
        .isEqualTo("GOLD");
  }
}
