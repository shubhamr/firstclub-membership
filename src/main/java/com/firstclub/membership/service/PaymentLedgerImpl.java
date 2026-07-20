package com.firstclub.membership.service;

import com.firstclub.membership.model.Payment;
import com.firstclub.membership.model.PaymentPurpose;
import com.firstclub.membership.model.PaymentStatus;
import com.firstclub.membership.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link PaymentLedger}. Persists the lifecycle of a {@link Payment} row — INTENDED →
 * CHARGED → APPLIED, plus the ABANDONED / IN_DOUBT / REFUNDED branches of {@link PaymentStatus} —
 * for each subscription charge.
 *
 * <p>Each method fixes its Spring transaction propagation to match how it is called. The two modes
 * used here:
 *
 * <ul>
 *   <li>{@code REQUIRED} joins the caller's transaction, so the write commits or rolls back
 *       atomically with the caller's work. {@code recordIntent} and {@code markApplied} use it: the
 *       intent commits together with the reservation, and APPLIED commits together with the
 *       subscription change, so a ledger row never describes a caller change that did not commit.
 *   <li>{@code REQUIRES_NEW} suspends any caller transaction and commits in its own, independently
 *       of whether the caller later commits or throws. {@code markCharged} and {@code
 *       markAbandoned} use it because they run between the caller's transactions, and a charge
 *       (money moved at the gateway) must be durable on its own: if a later apply throws, the
 *       CHARGED row still records that money moved, which is what lets recovery resume delivery.
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLedgerImpl implements PaymentLedger {

  private final PaymentRepository repository;
  private final Clock clock;

  @Transactional
  @Override
  public IntentOutcome recordIntent(
      Long subscriptionId,
      Long userId,
      BigDecimal amount,
      String reference,
      PaymentPurpose purpose) {
    Instant now = clock.instant();
    Payment existing = repository.findByReference(reference).orElse(null);

    if (existing == null) {
      // No in-session recovery from a duplicate key here: on a constraint violation this method
      // lets the caller roll back rather than catching and querying for the existing row. Because
      // this method joins the caller's transaction (REQUIRED), a constraint violation invalidates
      // the whole Hibernate persistence context, so catching it and querying around it yields a
      // Hibernate AssertionFailure rather than the existing row. A genuine race on the same
      // reference therefore rolls the caller back and surfaces as a 409, the same outcome the
      // uq_active_subscription_per_user index produces for concurrent subscribes. This rejects the
      // losing request before any money moves.
      repository.saveAndFlush(new Payment(subscriptionId, userId, reference, amount, purpose, now));
      return IntentOutcome.CHARGE_NOW;
    }

    return switch (existing.getStatus()) {
      // Genuinely finished. Nothing to charge, nothing to apply.
      case APPLIED, REFUNDED -> {
        log.debug("Payment ref={} already {} — nothing to do", reference, existing.getStatus());
        yield IntentOutcome.ALREADY_DONE;
      }
      // Money moved but the apply never happened. Return {@code IntentOutcome#ALREADY_CHARGED}:
      // the caller skips the gateway (no re-charge) but still runs the apply. This is the only
      // path that recovers a charged-not-applied payment; returning ALREADY_DONE instead would
      // leave the member charged and undelivered, because the retry would read it as finished.
      case CHARGED -> {
        log.warn(
            "Payment ref={} is CHARGED but not APPLIED — resuming delivery without re-charging",
            reference);
        yield IntentOutcome.ALREADY_CHARGED;
      }
      // A previous attempt was explicitly declined; re-arm it for this retry.
      case ABANDONED -> {
        existing.reArm(now);
        yield IntentOutcome.CHARGE_NOW;
      }
      // We do not know whether the previous attempt took money. Re-arm and call the gateway with
      // the SAME reference: its idempotency key replays the original outcome if a capture happened,
      // and charges if it did not. Asking the gateway is the only way to resolve this.
      case IN_DOUBT -> {
        log.warn("Payment ref={} was IN_DOUBT — re-asking the gateway to resolve it", reference);
        existing.reArm(now);
        yield IntentOutcome.CHARGE_NOW;
      }
      // A previous attempt died before reaching the gateway. Retry; the gateway dedupes on
      // reference, so at most one charge results.
      case INTENDED -> IntentOutcome.CHARGE_NOW;
    };
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void markCharged(String reference, String gatewayTxnId) {
    Payment payment = require(reference);
    // Idempotent: a duplicate scheduled-job run replays the gateway's original result and arrives
    // here a second time. Re-asserting CHARGED would throw an illegal-transition error that reads
    // like a payment fault when it is really just dedupe working, so absorb it.
    if (payment.getStatus() == PaymentStatus.CHARGED
        || payment.getStatus() == PaymentStatus.APPLIED) {
      log.debug(
          "Payment ref={} already {} — markCharged is a no-op", reference, payment.getStatus());
      return;
    }
    payment.markCharged(gatewayTxnId, clock.instant());
  }

  @Transactional
  @Override
  public void markApplied(String reference) {
    Payment payment = require(reference);
    if (payment.getStatus() == PaymentStatus.APPLIED) {
      return;
    }
    payment.markApplied(clock.instant());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void markAbandoned(String reference, String reason) {
    require(reference).markAbandoned(reason, clock.instant());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void markInDoubt(String reference, String reason) {
    Payment payment = require(reference);
    if (payment.getStatus() == PaymentStatus.CHARGED
        || payment.getStatus() == PaymentStatus.APPLIED) {
      return; // the call actually succeeded before the failure — not ambiguous after all
    }
    payment.markInDoubt(reason, clock.instant());
    log.error(
        "PAYMENT-IN-DOUBT ref={} reason={} — capture status unknown, awaiting reconciliation",
        reference,
        reason);
  }

  @Transactional
  @Override
  public void markRefunded(String reference) {
    require(reference).markRefunded(clock.instant());
  }

  @Transactional(readOnly = true)
  @Override
  public Optional<Payment> findByReference(String reference) {
    return repository.findByReference(reference);
  }

  @Transactional(readOnly = true)
  @Override
  public List<Payment> findStuckCharged(Instant cutoff, int limit) {
    return repository.findStuckCharged(cutoff, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  @Override
  public List<Payment> findInDoubt(Instant cutoff, int limit) {
    return repository.findInDoubt(cutoff, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  @Override
  public long countStuckCharged(Instant cutoff) {
    return repository.countByStatusAndCreatedAtBefore(PaymentStatus.CHARGED, cutoff);
  }

  @Transactional(readOnly = true)
  @Override
  public long countInDoubt(Instant cutoff) {
    return repository.countByStatusAndCreatedAtBefore(PaymentStatus.IN_DOUBT, cutoff);
  }

  @Transactional(readOnly = true)
  @Override
  public BigDecimal collectedFor(Long subscriptionId) {
    return repository.sumAppliedAmount(subscriptionId).orElse(BigDecimal.ZERO);
  }

  @Transactional(readOnly = true)
  @Override
  public Optional<String> appliedReferenceFor(Long subscriptionId) {
    return repository.findAppliedReference(subscriptionId, PageRequest.of(0, 1)).stream()
        .findFirst();
  }

  /**
   * Loads the {@link Payment} intent row for {@code reference}, throwing {@link
   * IllegalStateException} if none exists.
   *
   * <p>Every caller here (markCharged, markApplied, markAbandoned, etc.) records that money moved
   * or a charge resolved, and each runs against an intent {@code recordIntent} already persisted. A
   * missing reference therefore means the ledger lost that intent row — a bug. This throws rather
   * than returning an {@code Optional} the callers could ignore, so the failure is loud instead of
   * a silently skipped state transition on a real charge.
   */
  private Payment require(String reference) {
    return repository
        .findByReference(reference)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No payment intent for reference %s — the ledger lost a charge"
                        .formatted(reference)));
  }
}
