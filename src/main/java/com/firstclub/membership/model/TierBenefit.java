package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps a benefit onto a tier with per-tier params (e.g. {@code {"discountPct":15}}). This mapping
 * row is the unit of configuration: tuning a perk for a tier is an update here, not a code change.
 * The benefit is fetched LAZILY; the resolver uses an explicit fetch-join to avoid N+1.
 */
@Entity
@Table(name = "tier_benefit")
@Getter
public class TierBenefit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tier_id", nullable = false)
  private Long tierId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "benefit_id", nullable = false)
  private Benefit benefit;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> params = Map.of();

  @Column(nullable = false)
  private boolean active = true;

  protected TierBenefit() {}

  public Map<String, Object> getParams() {
    return params == null ? Map.of() : params;
  }
}
