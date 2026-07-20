package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Lifetime snapshot of a user's qualifying activity, maintained from order events and read by tier
 * evaluation.
 *
 * <p>Spend is deliberately <em>not</em> here. Order value qualifies a tier per calendar month, so
 * it lives per-month in {@link UserMonthlySpend}; a lifetime accumulator would never reset and
 * would eventually qualify everyone. Order count and cohorts are unqualified and stay
 * lifetime-scoped.
 */
@Entity
@Table(name = "user_order_stats")
@Getter
public class UserOrderStats {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "order_count", nullable = false)
  private int orderCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<String> cohorts = List.of();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected UserOrderStats() {}

  /** Hand-written: the jsonb column can read back null, and callers expect an empty list. */
  public List<String> getCohorts() {
    return cohorts == null ? List.of() : cohorts;
  }
}
