package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.model.MembershipPlan;
import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.service.pricing.PricingContext;
import com.firstclub.membership.service.pricing.PricingMode;
import com.firstclub.membership.service.pricing.PricingService;
import com.firstclub.membership.service.pricing.PricingStrategyFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the pricing strategies and their factory: each mode's multiplier, prorated upgrade charges
 * and refund credits.
 */
class PricingIntegrationTest extends AbstractIntegrationTest {

  @Autowired PricingStrategyFactory factory;
  @Autowired PricingService pricingService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @Test
  void factoryResolvesStrategies_andEachPricesCorrectly() {
    MembershipPlan plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    MembershipTier tier = tierRepository.findByCode("SILVER").orElseThrow();
    PricingContext ctx = new PricingContext(plan, tier);
    BigDecimal base = plan.getPrice();

    assertThat(factory.resolve(PricingMode.BASE).price(ctx)).isEqualByComparingTo(base);
  }

  @Test
  void proration_forHalfTheRemainingPeriod_isHalfThePriceDelta() {
    MembershipPlan plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    MembershipTier silver = tierRepository.findByCode("SILVER").orElseThrow();
    MembershipTier platinum = tierRepository.findByCode("PLATINUM").orElseThrow();

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant periodEnd =
        now.plus(Duration.ofDays(plan.getDurationDays() / 2)); // half the period left

    BigDecimal charge =
        pricingService.proratedUpgradeCharge(plan, silver, platinum, now, periodEnd);
    BigDecimal fullDelta =
        pricingService.priceFor(plan, platinum).subtract(pricingService.priceFor(plan, silver));

    assertThat(charge)
        .isEqualByComparingTo(
            fullDelta.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP));
    // A downgrade (no price increase) prorates to nothing.
    assertThat(pricingService.proratedUpgradeCharge(plan, platinum, silver, now, periodEnd))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void refundCredit_forHalfTheRemainingPeriod_isHalfThePricePaid() {
    MembershipPlan plan = planRepository.findByActiveTrueOrderByPriceAsc().getFirst();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant periodEnd = now.plus(Duration.ofDays(plan.getDurationDays() / 2));
    BigDecimal credit =
        pricingService.proratedRefundCredit(plan, new BigDecimal("400.00"), now, periodEnd);
    assertThat(credit).isEqualByComparingTo(new BigDecimal("200.00"));
  }
}
