package com.firstclub.membership.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of a user's qualifying activity, evaluated by the tier rule engine. Kept
 * separate from the {@code UserOrderStats} entity so rules never touch persistence.
 */
public record UserActivity(
    long userId, int orderCount, BigDecimal monthlySpend, Set<String> cohorts) {

  public static UserActivity of(
      long userId, int orderCount, BigDecimal monthlySpend, List<String> cohorts) {
    return new UserActivity(
        userId,
        orderCount,
        monthlySpend == null ? BigDecimal.ZERO : monthlySpend,
        cohorts == null ? Set.of() : Set.copyOf(cohorts));
  }

  public static UserActivity empty(long userId) {
    return new UserActivity(userId, 0, BigDecimal.ZERO, Set.of());
  }
}
