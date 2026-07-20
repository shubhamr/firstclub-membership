package com.firstclub.membership.service;

import static com.firstclub.membership.service.TransactionGuards.assertChargeableOutsideTransaction;

import com.firstclub.membership.exception.IllegalSubscriptionStateException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.model.EventType;
import com.firstclub.membership.model.PaymentPurpose;
import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionStatus;
import com.firstclub.membership.repository.SubscriptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled trial conversion and renewal/dunning for subscriptions. Dunning is the
 * retry-then-give-up handling of a declined recurring charge.
 *
 * <p>Both entry points run in three phases — <b>plan → charge → apply</b>: a short read-only
 * transaction plans the charge, the payment gateway is called with no transaction open, then a
 * second short transaction applies the outcome. The gateway is remote I/O, so it is charged between
 * the two transactions rather than inside one, keeping database locks and connections off the
 * remote round-trip. The phases are opened programmatically through {@link TransactionTemplate};
 * this bean carries no {@code @Transactional}, and its entry points must not gain one, because
 * {@link TransactionGuards#assertChargeableOutsideTransaction} throws when a gateway charge would
 * run with a transaction already open.
 */
@Service
@Slf4j
public class RenewalServiceImpl implements RenewalService {

  // Placeholder transaction reference for a resumed renewal: when the ledger shows this period was
  // already captured (intent is not CHARGE_NOW) the gateway is skipped, and
  // this value stands in for the real gateway reference in the applied outcome.
  private static final String RESUMED_REF_PREFIX = "resumed-";

  private final SubscriptionRepository subscriptionRepository;
  private final PaymentLedger paymentLedger;
  private final PaymentPort paymentPort;
  private final SubscriptionEvents subscriptionEvents;
  private final Clock clock;
  private final TransactionTemplate tx;

  /**
   * Dunning: max consecutive failed renewal charges before revoking, and the retry cadence (days).
   */
  private final int maxDunningAttempts;

  private final int[] retryScheduleDays;

  public RenewalServiceImpl(
      SubscriptionRepository subscriptionRepository,
      PaymentLedger paymentLedger,
      PaymentPort paymentPort,
      SubscriptionEvents subscriptionEvents,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${membership.renewal.max-dunning-attempts:4}") int maxDunningAttempts,
      @Value("${membership.renewal.retry-schedule-days:1,3,5,7}") int[] retryScheduleDays) {
    this.subscriptionRepository = subscriptionRepository;
    this.paymentLedger = paymentLedger;
    this.paymentPort = paymentPort;
    this.subscriptionEvents = subscriptionEvents;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
    // Fail fast on misconfiguration rather than at runtime deep inside the dunning path.
    if (retryScheduleDays == null || retryScheduleDays.length == 0) {
      throw new IllegalArgumentException(
          "membership.renewal.retry-schedule-days must define at least one interval");
    }
    if (maxDunningAttempts <= 0) {
      throw new IllegalArgumentException("membership.renewal.max-dunning-attempts must be > 0");
    }
    this.maxDunningAttempts = maxDunningAttempts;
    this.retryScheduleDays = retryScheduleDays.clone();
  }

  // ---------------------------------------------------------------------------------------------
  // Trial conversion — plan → charge → apply (or audit the failure)
  // ---------------------------------------------------------------------------------------------

  @Override
  public void convertTrial(Long subscriptionId) {
    assertChargeableOutsideTransaction();
    PendingCharge charge = tx.execute(status -> planTrialConversion(subscriptionId));
    if (charge == null) {
      return; // not a convertible trial
    }
    if (charge.chargeable()) {
      PaymentResult payment;
      try {
        payment = paymentPort.charge(charge.userId(), charge.amount(), charge.reference());
      } catch (RuntimeException gatewayFailure) {
        paymentLedger.markInDoubt(charge.reference(), gatewayFailure.toString());
        tx.executeWithoutResult(
            status -> recordTrialConversionFailure(subscriptionId, charge.amount()));
        throw gatewayFailure;
      }
      if (!payment.success()) {
        // Charge was declined (not an exception): leave the trial ACTIVE so a later conversion run
        // retries it, and record the failure so it is auditable rather than a silent lapse. The
        // record also advances the backoff (see recordTrialConversionFailure) so a
        // persistently declining card does not occupy the job's batch on every run.
        paymentLedger.markAbandoned(charge.reference(), payment.message());
        tx.executeWithoutResult(
            status -> recordTrialConversionFailure(subscriptionId, charge.amount()));
        return;
      }
      paymentLedger.markCharged(charge.reference(), payment.transactionRef());
    }
    try {
      tx.executeWithoutResult(status -> applyTrialConversion(subscriptionId, charge));
    } catch (RuntimeException applyFailed) {
      subscriptionEvents.chargedNotApplied(charge.reference(), subscriptionId, applyFailed);
      throw applyFailed;
    }
  }

  private PendingCharge planTrialConversion(Long subscriptionId) {
    Subscription sub = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    if (sub == null || sub.getTrialEnd() == null || sub.getStatus() != SubscriptionStatus.ACTIVE) {
      return null;
    }
    String reference = "trial-convert-%d".formatted(sub.getId());
    IntentOutcome intent =
        paymentLedger.recordIntent(
            sub.getId(),
            sub.getUserId(),
            sub.getPricePaid(),
            reference,
            PaymentPurpose.TRIAL_CONVERSION);
    if (intent == IntentOutcome.ALREADY_DONE) {
      return null; // already converted and paid for
    }
    return new PendingCharge(sub.getUserId(), sub.getPricePaid(), reference, intent);
  }

  private void applyTrialConversion(Long subscriptionId, PendingCharge charge) {
    Subscription sub = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    if (sub == null) {
      return;
    }
    Instant newExpiry = clock.instant().plus(Duration.ofDays(sub.getPlan().getDurationDays()));
    sub.convertTrial(newExpiry);
    paymentLedger.markApplied(charge.reference()); // atomic with the conversion
    subscriptionEvents.record(
        sub, EventType.TRIAL_CONVERTED, Map.of("charged", sub.getPricePaid()));
    subscriptionEvents.publish(
        sub, EventType.TRIAL_CONVERTED, "Your free trial has converted to a paid membership");
  }

  /**
   * Records a failed trial conversion and advances its backoff by setting the next-retry time.
   *
   * <p>The next-retry time is a field that {@link SubscriptionRepository#findConvertibleTrialIds}
   * filters on: that query selects due trials whose {@code nextRetryAt} has passed, ordered as a
   * page. Advancing the field each failure is what lets the conversion job drain — a card that
   * keeps declining is pushed past its retry time and out of the current page, so trials behind it
   * get a turn. Without it the same declining card stays at the head of page 0 and is re-charged
   * every run while nothing behind it converts. This reuses the dunning columns to schedule the
   * retry, sharing the backoff machinery with renewal.
   */
  private void recordTrialConversionFailure(Long subscriptionId, BigDecimal amount) {
    Subscription sub = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    if (sub == null) {
      return;
    }
    int attempt = Math.min(sub.getRenewalAttempts(), retryScheduleDays.length - 1);
    sub.markRenewalFailed(clock.instant().plus(Duration.ofDays(retryScheduleDays[attempt])));
    subscriptionEvents.record(sub, EventType.TRIAL_CONVERSION_FAILED, Map.of("amount", amount));
    subscriptionEvents.publish(
        sub,
        EventType.TRIAL_CONVERSION_FAILED,
        "We couldn't convert your free trial; we'll try again shortly");
  }

  // ---------------------------------------------------------------------------------------------
  // Renewal + dunning — plan → charge → apply outcome
  // ---------------------------------------------------------------------------------------------

  @Override
  public void renew(Long subscriptionId) {
    assertChargeableOutsideTransaction();
    RenewalPlan renewal = tx.execute(status -> planRenewal(subscriptionId));
    if (renewal == null) {
      return; // not renewable, or this period's renewal is already paid for and applied
    }
    // Reference is tied to the current period, so a retry of the same renewal dedupes but the next
    // period charges again.
    PaymentResult payment = PaymentResult.ok(RESUMED_REF_PREFIX + renewal.reference());
    if (renewal.chargeable()) {
      try {
        payment = paymentPort.charge(renewal.userId(), renewal.amount(), renewal.reference());
      } catch (RuntimeException gatewayFailure) {
        paymentLedger.markInDoubt(renewal.reference(), gatewayFailure.toString());
        throw gatewayFailure;
      }
      if (payment.success()) {
        paymentLedger.markCharged(renewal.reference(), payment.transactionRef());
      } else {
        paymentLedger.markAbandoned(renewal.reference(), payment.message());
      }
    }

    boolean charged = payment.success();
    try {
      tx.executeWithoutResult(status -> applyRenewalOutcome(subscriptionId, renewal, charged));
    } catch (RuntimeException applyFailed) {
      if (charged) {
        subscriptionEvents.chargedNotApplied(renewal.reference(), subscriptionId, applyFailed);
      }
      throw applyFailed;
    }
  }

  private RenewalPlan planRenewal(Long subscriptionId) {
    Subscription sub = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    if (sub == null
        || sub.getStatus() != SubscriptionStatus.ACTIVE
        || sub.getTrialEnd() != null
        || !sub.isAutoRenew()) {
      return null;
    }
    String reference = "renew-%d-%d".formatted(sub.getId(), sub.getExpiresAt().getEpochSecond());
    IntentOutcome intent =
        paymentLedger.recordIntent(
            sub.getId(), sub.getUserId(), sub.getPricePaid(), reference, PaymentPurpose.RENEWAL);
    if (intent == IntentOutcome.ALREADY_DONE) {
      return null; // this period was already charged and applied — never bill or extend twice
    }
    return new RenewalPlan(
        sub.getUserId(),
        sub.getPricePaid(),
        reference,
        sub.getExpiresAt(),
        sub.getPlan().getDurationDays(),
        intent);
  }

  private void applyRenewalOutcome(Long subscriptionId, RenewalPlan renewal, boolean charged) {
    Instant now = clock.instant();
    if (charged) {
      Instant from = renewal.expectedExpiry().isAfter(now) ? renewal.expectedExpiry() : now;
      Instant newExpiry = from.plus(Duration.ofDays(renewal.durationDays()));
      // Extend the paid-through date via a compare-and-set on the expected (pre-renewal) expiry:
      // SubscriptionRepository.extendIfUnchanged updates the row only if its expiry still
      // equals expectedExpiry. If another runner (a second instance, or an overlapping cron)
      // already
      // extended this period, the WHERE clause matches 0 rows and this stops, so one payment
      // extends
      // the period at most once. The guard lives in the DB row, so it holds regardless of any
      // process-level lock.
      int updated =
          subscriptionRepository.extendIfUnchanged(
              subscriptionId, renewal.expectedExpiry(), newExpiry);
      if (updated == 0) {
        reconcileFailedExtension(subscriptionId, renewal);
        return;
      }
      paymentLedger.markApplied(renewal.reference());
      Subscription renewed =
          subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
      if (renewed != null) {
        subscriptionEvents.record(renewed, EventType.RENEWED, Map.of("charged", renewal.amount()));
        subscriptionEvents.publish(renewed, EventType.RENEWED, "Your membership has renewed");
      }
      return;
    }

    Subscription sub = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    if (sub == null) {
      return;
    }
    // Dunning: retry on a schedule; revoke once attempts are exhausted.
    int attempt = sub.getRenewalAttempts() + 1;
    if (attempt >= maxDunningAttempts) {
      sub.markExpired();
      subscriptionEvents.record(
          sub, EventType.RENEWAL_FAILED, Map.of("attempt", attempt, "abandoned", true));
      subscriptionEvents.publish(
          sub, EventType.RENEWAL_FAILED, "We couldn't renew your membership; it has ended");
    } else {
      int days = retryScheduleDays[Math.min(attempt - 1, retryScheduleDays.length - 1)];
      sub.markRenewalFailed(now.plus(Duration.ofDays(days)));
      subscriptionEvents.record(
          sub, EventType.RENEWAL_FAILED, Map.of("attempt", attempt, "retryInDays", days));
      subscriptionEvents.publish(
          sub, EventType.RENEWAL_FAILED, "Payment for your renewal failed; we'll retry shortly");
    }
  }

  /**
   * Resolves the case where the renewal charge succeeded but {@link
   * SubscriptionRepository#extendIfUnchanged} matched no row. That has two causes with opposite
   * handling, which this method distinguishes:
   *
   * <ul>
   *   <li><b>A peer runner already extended this period</b> (still ACTIVE with a later expiry):
   *       benign — the ledger is APPLIED and the member has what they paid for, so this returns
   *       without extending again.
   *   <li><b>The row moved out from under this call</b> — the expiry sweep ({@link ExpirySweeper})
   *       flipped it to EXPIRED between the plan and apply phases — so the member is charged but
   *       not extended. This throws so the caller routes it to the charged-not-applied path ({@link
   *       SubscriptionEvents#chargedNotApplied}) rather than returning normally.
   * </ul>
   */
  private void reconcileFailedExtension(Long subscriptionId, RenewalPlan renewal) {
    Subscription current = subscriptionRepository.findByIdWithDetails(subscriptionId).orElse(null);
    boolean peerExtended =
        current != null
            && current.getStatus() == SubscriptionStatus.ACTIVE
            && current.getExpiresAt().isAfter(renewal.expectedExpiry());
    if (peerExtended) {
      log.info(
          "Renewal for sub {} was already extended by a peer runner — not extending again",
          subscriptionId);
      return;
    }
    throw new IllegalSubscriptionStateException(
        "Renewal for subscription %d could not be applied: expected expiry %s, found %s/%s"
            .formatted(
                subscriptionId,
                renewal.expectedExpiry(),
                current == null ? "missing" : current.getStatus(),
                current == null ? "-" : current.getExpiresAt()));
  }

  /**
   * A renewal charge plus the state it was planned against. {@code expectedExpiry} is carried so
   * the apply phase can compare-and-set on it, making the extension idempotent even if two runners
   * race.
   */
  private record RenewalPlan(
      long userId,
      BigDecimal amount,
      String reference,
      Instant expectedExpiry,
      int durationDays,
      IntentOutcome intent) {

    /** Skip the gateway when the ledger already knows this period was captured. */
    boolean chargeable() {
      return intent.shouldCallGateway();
    }
  }
}
