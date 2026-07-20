package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit of subscription lifecycle transitions. Indexed by (user_id, created_at). */
@Entity
@Table(name = "subscription_event")
public class SubscriptionEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subscription_id", nullable = false)
  private Long subscriptionId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EventType type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> detail = Map.of();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected SubscriptionEvent() {}

  public SubscriptionEvent(
      Long subscriptionId, Long userId, EventType type, Map<String, Object> detail) {
    this.subscriptionId = subscriptionId;
    this.userId = userId;
    this.type = type;
    this.detail = detail == null ? Map.of() : detail;
    this.createdAt = Instant.now();
  }
}
