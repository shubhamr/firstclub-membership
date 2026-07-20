package com.firstclub.membership.service.pricing;

import com.firstclub.membership.model.MembershipPlan;
import com.firstclub.membership.model.MembershipTier;
import java.math.BigDecimal;
import java.time.Instant;

/** Prices a subscription using the configured pricing strategy, including mid-cycle proration. */
public interface PricingService {

  /** Full-period price for a (plan, tier). */
  BigDecimal priceFor(MembershipPlan plan, MembershipTier tier);

  /**
   * Prorated charge for a mid-cycle upgrade: the price delta between tiers, scaled by the fraction
   * of the current period still remaining. Returns zero if it isn't a price increase (e.g. a
   * downgrade).
   */
  BigDecimal proratedUpgradeCharge(
      MembershipPlan plan,
      MembershipTier fromTier,
      MembershipTier toTier,
      Instant now,
      Instant periodEnd);

  /**
   * Prorated credit for an early cancel/refund: the unused fraction of the price the member paid
   * for the current period. The accounting counterpart to {@link #proratedUpgradeCharge}.
   */
  BigDecimal proratedRefundCredit(
      MembershipPlan plan, BigDecimal pricePaid, Instant now, Instant periodEnd);
}
