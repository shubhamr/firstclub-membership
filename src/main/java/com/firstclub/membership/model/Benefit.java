package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** A benefit definition in the catalog. Instances are mapped onto tiers via {@link TierBenefit}. */
@Entity
@Table(name = "benefit")
@Getter
public class Benefit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String code;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BenefitType type;

  @Column(nullable = false)
  private String name;

  @Column private String description;

  @Column(nullable = false)
  private boolean active = true;

  protected Benefit() {}

  public Benefit(String code, BenefitType type, String name, String description) {
    this.code = code;
    this.type = type;
    this.name = name;
    this.description = description;
    this.active = true;
  }

  public String getDescription() {
    return description;
  }
}
