package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One order's contribution to a user's spend, the granular source behind {@link UserMonthlySpend}.
 *
 * <p>{@code orderId} is the primary key, so re-delivering the same order under at-least-once
 * delivery updates the row in place instead of double-counting. The month a spend event counts
 * toward is derived from {@code occurredAt}, never from when the server received it.
 *
 * <p>Read-only from Java, like {@link UserOrderStats} and {@link UserMonthlySpend}. Writes go
 * through the repository upsert so concurrent deliveries stay race-safe.
 */
@Entity
@Table(name = "user_order_event")
@Getter
public class UserOrderEvent {

  @Id
  @Column(name = "order_id")
  private Long orderId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected UserOrderEvent() {}
}
