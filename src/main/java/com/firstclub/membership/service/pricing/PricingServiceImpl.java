package com.firstclub.membership.service.pricing;

import com.firstclub.membership.model.MembershipPlan;
import com.firstclub.membership.model.MembershipTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Facade over the pricing strategies. The active mode is configuration (default BASE), so a new
 * pricing behaviour reaches production without a change to this class or to the API.
 */
@Service
public class PricingServiceImpl implements PricingService {

  private final PricingStrategyFactory factory;
  private final PricingMode mode;

  public PricingServiceImpl(
      PricingStrategyFactory factory, @Value("${membership.pricing.mode:BASE}") PricingMode mode) {
    this.factory = factory;
    this.mode = mode;
  }

  @Override
  public BigDecimal priceFor(MembershipPlan plan, MembershipTier tier) {
    return factory.resolve(mode).price(new PricingContext(plan, tier));
  }

  @Override
  public BigDecimal proratedUpgradeCharge(
      MembershipPlan plan,
      MembershipTier fromTier,
      MembershipTier toTier,
      Instant now,
      Instant periodEnd) {
    BigDecimal delta = priceFor(plan, toTier).subtract(priceFor(plan, fromTier));
    if (delta.signum() <= 0) {
      return BigDecimal.ZERO; // not a price increase (e.g. a downgrade) — nothing to charge
    }
    return delta
        .multiply(remainingFraction(plan, now, periodEnd))
        .setScale(2, RoundingMode.HALF_UP);
  }

  @Override
  public BigDecimal proratedRefundCredit(
      MembershipPlan plan, BigDecimal pricePaid, Instant now, Instant periodEnd) {
    if (pricePaid == null || pricePaid.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return pricePaid
        .multiply(remainingFraction(plan, now, periodEnd))
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * The fraction of the current billing period still remaining, in {@code [0, 1]}. Shared by both
   * proration paths (upgrade charge and refund credit) so the time-math lives in exactly one place.
   */
  private BigDecimal remainingFraction(MembershipPlan plan, Instant now, Instant periodEnd) {
    long totalSeconds = Duration.ofDays(plan.getDurationDays()).toSeconds();
    if (totalSeconds <= 0) {
      return BigDecimal.ZERO;
    }
    long remainingSeconds = Math.max(0, Duration.between(now, periodEnd).toSeconds());
    return BigDecimal.valueOf(remainingSeconds)
        .divide(BigDecimal.valueOf(totalSeconds), 10, RoundingMode.HALF_UP)
        .min(BigDecimal.ONE);
  }
}
