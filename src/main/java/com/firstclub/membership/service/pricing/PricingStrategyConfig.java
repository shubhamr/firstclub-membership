package com.firstclub.membership.service.pricing;

import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one {@link MultiplierPricingStrategy} per {@link PricingMode}.
 *
 * <p>A second pricing behaviour is a second bean here plus a {@link PricingMode} constant — the
 * factory resolves by mode and the subscribe flow never learns which strategy ran.
 */
@Configuration
public class PricingStrategyConfig {

  /** Base = the plan's list price scaled by the tier premium only. */
  @Bean
  PricingStrategy basePricingStrategy() {
    return new MultiplierPricingStrategy(PricingMode.BASE, BigDecimal.ONE);
  }
}
