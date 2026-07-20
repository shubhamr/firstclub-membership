package com.firstclub.membership.dto;

import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The "current membership + expiry" view: plan, tier, unlocked benefits and lifecycle status. */
public record MembershipView(
    boolean active,
    Long subscriptionId,
    long userId,
    PlanDto plan,
    String tierCode,
    String tierName,
    int tierRank,
    List<ResolvedBenefit> benefits,
    String status,
    BigDecimal pricePaid,
    Instant trialEnd,
    Instant startAt,
    Instant expiresAt) {

  public static MembershipView inactive(long userId) {
    return new MembershipView(
        false, null, userId, null, null, null, 0, List.of(), "NONE", null, null, null, null);
  }

  public static MembershipView of(Subscription s, List<ResolvedBenefit> benefits) {
    boolean active = s.getStatus() == SubscriptionStatus.ACTIVE;
    return new MembershipView(
        active,
        s.getId(),
        s.getUserId(),
        PlanDto.from(s.getPlan()),
        s.getTier().getCode(),
        s.getTier().getName(),
        s.getTier().getRank(),
        benefits,
        s.getStatus().name(),
        s.getPricePaid(),
        s.getTrialEnd(),
        s.getStartAt(),
        s.getExpiresAt());
  }
}
