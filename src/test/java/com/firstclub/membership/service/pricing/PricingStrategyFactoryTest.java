package com.firstclub.membership.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the strategy registry: every {@link PricingMode} resolves, and both a duplicate claim and a
 * missing claim fail at construction.
 *
 * <p>Plain JUnit rather than an integration test: the factory takes its strategies on the
 * constructor.
 */
class PricingStrategyFactoryTest {

  private static PricingStrategy strategy(PricingMode mode, String multiplier) {
    return new MultiplierPricingStrategy(mode, new BigDecimal(multiplier));
  }

  private static List<PricingStrategy> allModes() {
    return List.of(strategy(PricingMode.BASE, "1"));
  }

  @Test
  void everyModeResolves() {
    PricingStrategyFactory factory = new PricingStrategyFactory(allModes());

    for (PricingMode mode : PricingMode.values()) {
      assertThat(factory.resolve(mode).mode()).isEqualTo(mode);
    }
  }

  /**
   * A duplicate claim must fail fast. Otherwise the winner is whichever bean Spring injected last,
   * and every subsequent charge is silently mispriced.
   */
  @Test
  void twoStrategiesClaimingTheSameMode_failFast() {
    List<PricingStrategy> clashing =
        List.of(strategy(PricingMode.BASE, "1"), strategy(PricingMode.BASE, "9"));

    assertThatThrownBy(() -> new PricingStrategyFactory(clashing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BASE");
  }

  /** A mode with no strategy must fail at boot, not on the first subscribe that selects it. */
  @Test
  void modeWithNoStrategy_failsAtStartup() {
    assertThatThrownBy(() -> new PricingStrategyFactory(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No pricing strategy registered");
  }
}
