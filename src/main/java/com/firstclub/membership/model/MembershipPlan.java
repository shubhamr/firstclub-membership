package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;

/** A membership plan: a billing cadence with its price and coverage window. */
@Entity
@Table(name = "membership_plan")
@Getter
public class MembershipPlan {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BillingCadence cadence;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private BigDecimal price;

  @Column(name = "duration_days", nullable = false)
  private int durationDays;

  @Column(name = "trial_days", nullable = false)
  private int trialDays;

  @Column(nullable = false)
  private boolean active = true;

  protected MembershipPlan() {}
}
