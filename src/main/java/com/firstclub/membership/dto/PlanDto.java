package com.firstclub.membership.dto;

import com.firstclub.membership.model.MembershipPlan;
import java.math.BigDecimal;

/**
 * API view of a plan. Kept separate from the entity so persistence changes never leak to clients.
 */
public record PlanDto(
    Long id, String cadence, String name, BigDecimal price, int durationDays, int trialDays) {

  public static PlanDto from(MembershipPlan p) {
    return new PlanDto(
        p.getId(),
        p.getCadence().name(),
        p.getName(),
        p.getPrice(),
        p.getDurationDays(),
        p.getTrialDays());
  }
}
