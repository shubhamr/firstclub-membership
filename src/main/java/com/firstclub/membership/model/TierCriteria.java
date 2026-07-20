package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Configurable progression criteria for a tier. Every threshold is nullable — a null threshold
 * means "this rule does not gate this tier". The rule engine ({@link
 * com.firstclub.membership.service.rules}) reads these values, so tuning progression is a data
 * change, not a code change.
 */
@Entity
@Table(name = "tier_criteria")
@Getter
public class TierCriteria {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tier_id", nullable = false)
  private Long tierId;

  @Column(name = "min_orders")
  private Integer minOrders;

  @Column(name = "min_monthly_spend")
  private BigDecimal minMonthlySpend;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<String> cohorts = List.of();

  protected TierCriteria() {}

  public List<String> getCohorts() {
    return cohorts == null ? List.of() : cohorts;
  }
}
