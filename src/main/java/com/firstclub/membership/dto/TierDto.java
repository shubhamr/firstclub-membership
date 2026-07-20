package com.firstclub.membership.dto;

import com.firstclub.membership.model.MembershipTier;
import java.math.BigDecimal;
import java.util.List;

/** API view of a tier and the benefits it unlocks. */
public record TierDto(
    Long id,
    String code,
    String name,
    int rank,
    BigDecimal priceMultiplier,
    List<ResolvedBenefit> benefits) {

  public static TierDto from(MembershipTier tier, List<ResolvedBenefit> benefits) {
    return new TierDto(
        tier.getId(),
        tier.getCode(),
        tier.getName(),
        tier.getRank(),
        tier.getPriceMultiplier(),
        benefits);
  }
}
