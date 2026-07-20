package com.firstclub.membership.service;

import com.firstclub.membership.model.Payment;
import com.firstclub.membership.model.PaymentPurpose;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The local system of record for money, wrapped around every gateway charge.
 *
 * <p>Call order is always: {@link #recordIntent} (inside the planning transaction, so it commits
 * before any money moves) → charge the gateway → {@link #markCharged} or {@link #markAbandoned} →
 * {@link #markApplied} (inside the applying transaction, so ledger and subscription commit
 * together).
 */
public interface PaymentLedger {

  /**
   * Record the intent to charge, idempotently on {@code reference}.
   *
   * @return what the caller should do next. This makes the ledger an idempotency barrier for the
   *     <em>state transition</em>; gateway idempotency only dedupes the money, not the subscription
   *     change that follows it. {@link IntentOutcome#ALREADY_CHARGED} tells a retry to skip the
   *     gateway but still apply, so a charge that took money and failed to deliver heals on the
   *     next attempt instead of stalling.
   */
  IntentOutcome recordIntent(
      Long subscriptionId,
      Long userId,
      BigDecimal amount,
      String reference,
      PaymentPurpose purpose);

  /** The gateway approved. Runs in its own transaction, between the charge and the apply. */
  void markCharged(String reference, String gatewayTxnId);

  /** The subscription change committed — call inside that same transaction. */
  void markApplied(String reference);

  /** Explicitly declined; money definitively did not move. Runs in its own transaction. */
  void markAbandoned(String reference, String reason);

  /**
   * The gateway call failed ambiguously (timeout, reset, open breaker) — we do not know whether
   * money moved. Runs in its own transaction. Never use this for an explicit decline.
   */
  void markInDoubt(String reference, String reason);

  /** Reversed after a refund or chargeback — call inside the refunding transaction. */
  void markRefunded(String reference);

  /** The ledger row for a reference, if any. */
  Optional<Payment> findByReference(String reference);

  /** Charges that took money and never delivered, older than {@code cutoff}. */
  List<Payment> findStuckCharged(Instant cutoff, int limit);

  /** Ambiguous outcomes awaiting resolution against the gateway, older than {@code cutoff}. */
  List<Payment> findInDoubt(Instant cutoff, int limit);

  /**
   * Total stuck/ambiguous counts, unpaged — the alerting gauges must reflect the whole population,
   * not the size of whatever page reconciliation happened to fetch.
   */
  long countStuckCharged(Instant cutoff);

  long countInDoubt(Instant cutoff);

  /**
   * Total money actually collected (APPLIED) for a subscription — the only correct basis for a
   * refund credit. Zero when nothing was collected.
   */
  BigDecimal collectedFor(Long subscriptionId);

  /** Reference of the APPLIED payment for a subscription, if one exists — used to reverse it. */
  Optional<String> appliedReferenceFor(Long subscriptionId);
}
