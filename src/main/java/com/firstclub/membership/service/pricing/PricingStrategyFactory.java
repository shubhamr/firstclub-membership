package com.firstclub.membership.service.pricing;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Factory that resolves the {@link PricingStrategy} for a {@link PricingMode}. Strategies
 * self-register (Spring injects every {@code PricingStrategy} bean), so adding a mode needs no
 * change here.
 */
@Component
public class PricingStrategyFactory {

  private final Map<PricingMode, PricingStrategy> byMode = new EnumMap<>(PricingMode.class);

  public PricingStrategyFactory(List<PricingStrategy> strategies) {
    for (PricingStrategy strategy : strategies) {
      PricingStrategy clash = byMode.put(strategy.mode(), strategy);
      if (clash != null) {
        // Map.put would otherwise let the winner be decided by bean-injection order — a silent,
        // arbitrary mispricing. Two strategies claiming a mode is a wiring bug; say so loudly.
        throw new IllegalStateException(
            "Two pricing strategies registered for mode %s: %s and %s"
                .formatted(
                    strategy.mode(), clash.getClass().getName(), strategy.getClass().getName()));
      }
    }
    // Fail at startup rather than on the first subscribe that happens to select the missing mode.
    for (PricingMode mode : PricingMode.values()) {
      if (!byMode.containsKey(mode)) {
        throw new IllegalStateException("No pricing strategy registered for mode " + mode);
      }
    }
  }

  /** Never null: the constructor has already proven every mode resolves. */
  public PricingStrategy resolve(PricingMode mode) {
    return byMode.get(mode);
  }
}
