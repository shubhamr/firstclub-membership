package com.firstclub.membership.model;

import com.firstclub.membership.exception.IllegalSubscriptionStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * One charge against the payment gateway — the local system of record for money.
 *
 * <p>It exists for detectability. {@code reserve → charge → apply} can take money and then fail to
 * apply it — a concurrent cancel, a lost optimistic-lock race, a crash between phases. Without a
 * durable local row asserting the charge happened, that gap is only findable by reconciling against
 * the gateway by hand. With one, {@code status = CHARGED} past a grace window <em>is</em> the
 * alert.
 *
 * <p>{@link #reference} is both the gateway idempotency key and this table's unique key, so a
 * replayed charge can never produce a second ledger row.
 */
@Entity
@Table(name = "payment")
@Getter
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subscription_id", nullable = false)
  private Long subscriptionId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(nullable = false, unique = true)
  private String reference;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency = "INR";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus status;

  @Column(name = "gateway_txn_id")
  private String gatewayTxnId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentPurpose purpose;

  @Column(name = "failure_reason")
  private String failureReason;

  // JPA-internal optimistic-lock counter: Hibernate owns it, callers have no business reading it.
  @Version
  @Column(nullable = false)
  @Getter(AccessLevel.NONE)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Payment() {}

  public Payment(
      Long subscriptionId,
      Long userId,
      String reference,
      BigDecimal amount,
      PaymentPurpose purpose,
      Instant now) {
    this.subscriptionId = subscriptionId;
    this.userId = userId;
    this.reference = reference;
    this.amount = amount;
    this.purpose = purpose;
    this.status = PaymentStatus.INTENDED;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** The gateway approved: money has moved and delivery is now owed. */
  public void markCharged(String gatewayTxnId, Instant now) {
    transitionTo(PaymentStatus.CHARGED, now);
    this.gatewayTxnId = gatewayTxnId;
  }

  /**
   * The subscription change committed. Written in the <em>same</em> transaction as that change, so
   * the ledger and the subscription can never disagree.
   */
  public void markApplied(Instant now) {
    transitionTo(PaymentStatus.APPLIED, now);
  }

  /** Explicitly declined by the gateway — money definitively did not move. */
  public void markAbandoned(String reason, Instant now) {
    transitionTo(PaymentStatus.ABANDONED, now);
    this.failureReason = reason;
  }

  /**
   * The gateway call failed ambiguously, leaving it unknown whether the charge captured. Distinct
   * from {@link #markAbandoned}, which asserts that no money moved.
   */
  public void markInDoubt(String reason, Instant now) {
    transitionTo(PaymentStatus.IN_DOUBT, now);
    this.failureReason = reason;
  }

  /** Re-arm an abandoned attempt for a retry on the same logical charge. */
  public void reArm(Instant now) {
    transitionTo(PaymentStatus.INTENDED, now);
    this.failureReason = null;
  }

  public void markRefunded(Instant now) {
    transitionTo(PaymentStatus.REFUNDED, now);
  }

  private void transitionTo(PaymentStatus target, Instant now) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalSubscriptionStateException(
          "Payment %s cannot move from %s to %s".formatted(reference, status, target));
    }
    this.status = target;
    this.updatedAt = now;
  }
}
