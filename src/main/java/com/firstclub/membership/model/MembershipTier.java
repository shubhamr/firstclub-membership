package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;

/**
 * A membership tier (Silver/Gold/Platinum...). Tiers are rows, not a code enum, so new tiers are a
 * config change. {@code rank} defines ordering: higher rank = richer tier. {@code priceMultiplier}
 * is the tier's premium over the plan's base price.
 */
@Entity
@Table(name = "membership_tier")
@Getter
public class MembershipTier {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private int rank;

  @Column(nullable = false)
  private boolean active = true;

  @Column(name = "price_multiplier", nullable = false)
  private BigDecimal priceMultiplier = BigDecimal.ONE;

  protected MembershipTier() {}
}
