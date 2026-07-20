package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/** A credit issued to a member for the unused portion of a period (early cancel / refund). */
@Entity
@Table(name = "credit_note")
@Getter
public class CreditNote {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subscription_id", nullable = false)
  private Long subscriptionId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(nullable = false)
  private String reason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected CreditNote() {}

  public CreditNote(Long subscriptionId, Long userId, BigDecimal amount, String reason) {
    this.subscriptionId = subscriptionId;
    this.userId = userId;
    this.amount = amount;
    this.reason = reason;
    this.createdAt = Instant.now();
  }
}
