package com.firstclub.membership.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prices a plan as list price × the tier's premium × a mode-specific multiplier.
 *
 * <p>Every pricing mode is this same calculation with a different constant, so it is one class
 * configured three ways rather than three classes with identical bodies (see {@link
 * PricingStrategyConfig}). A mode needing genuinely different arithmetic — reading demand, region
 * or time from {@link PricingContext} instead of a constant — implements {@link PricingStrategy}
 * directly.
 *
 * <p>Not a {@code @Component}: it is registered once per mode as a {@code @Bean}, and a stereotype
 * would add a fourth, mode-ambiguous instance.
 */
public class MultiplierPricingStrategy implements PricingStrategy {

  private final PricingMode mode;
  private final BigDecimal multiplier;

  public MultiplierPricingStrategy(PricingMode mode, BigDecimal multiplier) {
    this.mode = mode;
    this.multiplier = multiplier;
  }

  @Override
  public BigDecimal price(PricingContext context) {
    return context
        .plan()
        .getPrice()
        .multiply(context.tier().getPriceMultiplier())
        .multiply(multiplier)
        .setScale(2, RoundingMode.HALF_UP);
  }

  @Override
  public PricingMode mode() {
    return mode;
  }
}
