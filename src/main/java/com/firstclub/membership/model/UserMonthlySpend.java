package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * A user's qualifying spend for one calendar month ({@code YYYY-MM}, UTC).
 *
 * <p>One row per user per month, so "total order value in a month" is a lookup rather than a
 * running total: a month with no row is zero spend, which is what makes last month's spending stop
 * qualifying this month's tier.
 *
 * <p>Read-only from Java, like {@link UserOrderStats} — writes go through the repository's upsert
 * so concurrent order events can neither lose an update nor collide on insert.
 */
@Entity
@Table(name = "user_monthly_spend")
@IdClass(UserMonthlySpend.Key.class)
@Getter
public class UserMonthlySpend {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Id
  @Column(name = "year_month", length = 7)
  private String yearMonth;

  @Column(nullable = false)
  private BigDecimal amount = BigDecimal.ZERO;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected UserMonthlySpend() {}

  /** Composite primary key. JPA requires it to be serializable with value equality. */
  public static class Key implements Serializable {

    private Long userId;
    private String yearMonth;

    protected Key() {}

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other instanceof Key key
          && Objects.equals(userId, key.userId)
          && Objects.equals(yearMonth, key.yearMonth);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, yearMonth);
    }
  }
}
