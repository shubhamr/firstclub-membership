package com.firstclub.membership.dto;

import com.firstclub.membership.model.Benefit;
import com.firstclub.membership.model.TierBenefit;
import java.util.Map;

/**
 * A benefit as applied to a tier, with its effective params. This is what checkout/delivery
 * consume.
 */
public record ResolvedBenefit(
    String code, String type, String name, String description, Map<String, Object> params) {

  public static ResolvedBenefit from(TierBenefit tb) {
    Benefit b = tb.getBenefit();
    return new ResolvedBenefit(
        b.getCode(), b.getType().name(), b.getName(), b.getDescription(), tb.getParams());
  }
}
