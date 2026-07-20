package com.firstclub.membership.service.pricing;

import java.math.BigDecimal;

/**
 * Strategy for computing the price of a subscription. New pricing behaviour (surge, regional,
 * promo) is a new implementation + a {@link PricingMode} — the subscribe flow never changes.
 */
public interface PricingStrategy {

  BigDecimal price(PricingContext context);

  /** The mode this strategy handles; used by {@link PricingStrategyFactory} to resolve it. */
  PricingMode mode();
}
