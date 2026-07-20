package com.firstclub.membership.model;

import com.firstclub.membership.exception.IllegalSubscriptionStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * A user's membership subscription.
 *
 * <p><b>Concurrency:</b> {@link #version} enables JPA optimistic locking. Two concurrent mutations
 * (e.g. an upgrade racing an auto-reevaluation) cannot both commit — the loser gets an {@code
 * OptimisticLockException} (surfaced as HTTP 409), so no update is silently lost. Associations are
 * LAZY to avoid dragging plan/tier rows into every query; reads that need them fetch explicitly.
 */
@Entity
@Table(name = "subscription")
@Getter
public class Subscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_id", nullable = false)
  private MembershipPlan plan;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tier_id", nullable = false)
  private MembershipTier tier;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SubscriptionStatus status;

  @Column(name = "start_at", nullable = false)
  private Instant startAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "auto_renew", nullable = false)
  private boolean autoRenew = true;

  // Price locked at purchase (grandfathering): later plan price changes never touch this
  // subscription.
  @Column(name = "price_paid", nullable = false)
  private BigDecimal pricePaid;

  // Set while the subscription is in a free trial; cleared when the trial converts to paid.
  @Column(name = "trial_end")
  private Instant trialEnd;

  // Dunning: consecutive failed renewal charges and when the next retry is due.
  @Column(name = "renewal_attempts", nullable = false)
  private int renewalAttempts;

  @Column(name = "next_retry_at")
  private Instant nextRetryAt;

  // Hibernate-managed optimistic-lock counter; deliberately not exposed to callers.
  @Version
  @Column(nullable = false)
  @Getter(AccessLevel.NONE)
  private long version;

  protected Subscription() {}

  public Subscription(
      Long userId,
      MembershipPlan plan,
      MembershipTier tier,
      BigDecimal pricePaid,
      Instant startAt,
      Instant expiresAt) {
    this.userId = userId;
    this.plan = plan;
    this.tier = tier;
    this.pricePaid = pricePaid;
    this.status = SubscriptionStatus.ACTIVE;
    this.startAt = startAt;
    this.expiresAt = expiresAt;
    this.autoRenew = true;
  }

  /**
   * Create a subscription in {@link SubscriptionStatus#PENDING} — a reservation held while the
   * gateway is charged <em>outside</em> the database transaction. It becomes visible to the
   * one-live-subscription unique index immediately, so a concurrent second attempt is rejected
   * before any money moves; it is then {@link #activate() activated} on a successful charge or
   * {@link #abandonReservation() abandoned} on a declined one.
   */
  public static Subscription pendingReservation(
      Long userId,
      MembershipPlan plan,
      MembershipTier tier,
      BigDecimal pricePaid,
      Instant startAt,
      Instant expiresAt) {
    Subscription s = new Subscription(userId, plan, tier, pricePaid, startAt, expiresAt);
    s.status = SubscriptionStatus.PENDING;
    return s;
  }

  /** Confirm a reservation once its charge has succeeded: {@code PENDING → ACTIVE}. */
  public void activate() {
    transitionTo(SubscriptionStatus.ACTIVE);
  }

  /** Release a reservation whose charge was declined: {@code PENDING → CANCELLED}. */
  public void abandonReservation() {
    transitionTo(SubscriptionStatus.CANCELLED);
  }

  public void changeTier(MembershipTier newTier) {
    if (!status.isModifiable()) {
      throw new IllegalSubscriptionStateException(
          "Subscription %d is %s and cannot change tier".formatted(id, status));
    }
    this.tier = newTier;
  }

  /** Update the locked price (e.g. after a tier change), so renewals reflect the new tier. */
  public void reprice(BigDecimal newPricePaid) {
    requireStatus(SubscriptionStatus.ACTIVE);
    this.pricePaid = newPricePaid;
  }

  /**
   * Begin a free trial ending at {@code trialEnd} (no charge until then).
   *
   * <p>Only on a reservation, before activation. Stamping {@code trialEnd} on an active
   * subscription would take it out of {@code findDueForRenewal} — which filters on {@code trialEnd
   * is null}, so billing stops silently — and hand it to the trial-conversion job, whose {@link
   * #convertTrial} overwrites {@code expiresAt} outright rather than extending it. That turns a
   * paid membership into a free one.
   */
  public void beginTrial(Instant trialEnd) {
    requireStatus(SubscriptionStatus.PENDING);
    this.trialEnd = trialEnd;
  }

  /** Convert a trial to paid: clear the trial and extend the paid-through date. */
  public void convertTrial(Instant newExpiresAt) {
    requireStatus(SubscriptionStatus.ACTIVE);
    if (trialEnd == null) {
      throw new IllegalSubscriptionStateException(
          "Subscription %d is not in a trial and cannot be converted".formatted(id));
    }
    this.trialEnd = null;
    this.expiresAt = newExpiresAt;
  }

  /** Successful renewal: extend the paid-through date and reset dunning state. */
  public void markRenewed(Instant newExpiresAt) {
    requireStatus(SubscriptionStatus.ACTIVE);
    this.expiresAt = newExpiresAt;
    this.renewalAttempts = 0;
    this.nextRetryAt = null;
  }

  /** Failed renewal charge: bump the dunning counter and schedule the next retry. */
  public void markRenewalFailed(Instant nextRetryAt) {
    requireStatus(SubscriptionStatus.ACTIVE);
    this.renewalAttempts += 1;
    this.nextRetryAt = nextRetryAt;
  }

  /**
   * Guard for mutators that change lifecycle-relevant fields <em>without</em> changing status.
   *
   * <p>{@link #transitionTo} only protects status changes, so without this guard those mutators
   * would bypass the state machine: a renewal planned against an ACTIVE row could have the expiry
   * sweep mark it EXPIRED underneath it, and the apply phase would extend an EXPIRED subscription —
   * leaving a charged member with no access.
   */
  private void requireStatus(SubscriptionStatus... allowed) {
    for (SubscriptionStatus candidate : allowed) {
      if (status == candidate) {
        return;
      }
    }
    throw new IllegalSubscriptionStateException(
        "Subscription %d is %s; this operation requires one of %s"
            .formatted(id, status, Arrays.toString(allowed)));
  }

  public void cancel() {
    transitionTo(SubscriptionStatus.CANCELLED);
    this.autoRenew = false;
  }

  public void markExpired() {
    transitionTo(SubscriptionStatus.EXPIRED);
  }

  /** Single guarded transition point — the state machine decides what is legal. */
  private void transitionTo(SubscriptionStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalSubscriptionStateException(
          "Subscription %d cannot move from %s to %s".formatted(id, status, target));
    }
    this.status = target;
  }
}
