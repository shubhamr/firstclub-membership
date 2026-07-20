package com.firstclub.membership.service;

import static com.firstclub.membership.service.TransactionGuards.assertChargeableOutsideTransaction;

import com.firstclub.membership.dto.CreditNoteDto;
import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.event.MembershipLifecycleEvent;
import com.firstclub.membership.exception.BusinessRuleException;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.exception.PaymentFailedException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.model.*;
import com.firstclub.membership.repository.AppUserRepository;
import com.firstclub.membership.repository.CreditNoteRepository;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.pricing.PricingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscription lifecycle: subscribe, upgrade, downgrade, cancel, view, and tier re-evaluation.
 *
 * <p>This is the concurrency-critical service. Every mutation relies on three layered guards:
 *
 * <ol>
 *   <li>the {@code uq_active_subscription_per_user} partial unique index (one live sub per user),
 *   <li>{@code @Version} optimistic locking on {@link Subscription} (no lost updates), and
 *   <li>idempotency keys at the controller edge (retries don't double-mutate).
 * </ol>
 *
 * <p><b>Payments run outside transactions.</b> A gateway call is remote I/O, and holding a pooled
 * JDBC connection across one is how a slow gateway drains the pool and takes unrelated endpoints
 * down with it. Paying operations therefore go <b>reserve → charge → activate</b>: a short
 * transaction records intent and commits (releasing its connection), the gateway is charged with no
 * transaction open, then a second short transaction applies the result. Non-paying mutations
 * (downgrade, cancel, reads) stay plain {@code @Transactional} methods.
 *
 * <p>Pricing is delegated to a {@link PricingService} strategy; side effects (notifications) are
 * published as {@link MembershipLifecycleEvent}s rather than called inline. Expiry is computed
 * lazily on read and persisted only by the scheduled sweep — reads never write.
 */
@Service
@Slf4j
public class SubscriptionServiceImpl implements SubscriptionService {

  private final SubscriptionRepository subscriptionRepository;
  private final AppUserRepository appUserRepository;
  private final SubscriptionEventRepository eventRepository;
  private final CreditNoteRepository creditNoteRepository;
  private final PlanService planService;
  private final TierService tierService;
  private final TierAssignmentService tierAssignmentService;
  private final UserActivityService userActivityService;
  private final BenefitResolver benefitResolver;
  private final PaymentPort paymentPort;
  private final SubscriptionEvents subscriptionEvents;
  private final PaymentLedger paymentLedger;
  private final PricingService pricingService;
  private final Clock clock;

  /**
   * Runs the short DB phases of reserve → charge → activate as discrete, committed transactions.
   */
  private final TransactionTemplate tx;

  /**
   * Grace window after {@code expiresAt} during which benefits continue while renewal/dunning is
   * attempted — so a failed renewal or an in-flight payment doesn't instantly revoke a paying
   * member.
   */
  private final Duration grace;

  /** How long a PENDING reservation may live before the reaper treats it as orphaned. */
  private final Duration reservationTtl;

  public SubscriptionServiceImpl(
      SubscriptionRepository subscriptionRepository,
      AppUserRepository appUserRepository,
      SubscriptionEventRepository eventRepository,
      CreditNoteRepository creditNoteRepository,
      PlanService planService,
      TierService tierService,
      TierAssignmentService tierAssignmentService,
      UserActivityService userActivityService,
      BenefitResolver benefitResolver,
      PaymentPort paymentPort,
      SubscriptionEvents subscriptionEvents,
      PaymentLedger paymentLedger,
      PricingService pricingService,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${membership.grace-period-days:3}") long graceDays,
      @Value("${membership.reservation-ttl-minutes:15}") long reservationTtlMinutes) {
    this.subscriptionRepository = subscriptionRepository;
    this.appUserRepository = appUserRepository;
    this.eventRepository = eventRepository;
    this.creditNoteRepository = creditNoteRepository;
    this.planService = planService;
    this.tierService = tierService;
    this.tierAssignmentService = tierAssignmentService;
    this.userActivityService = userActivityService;
    this.benefitResolver = benefitResolver;
    this.paymentPort = paymentPort;
    this.subscriptionEvents = subscriptionEvents;
    this.paymentLedger = paymentLedger;
    this.pricingService = pricingService;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
    this.grace = Duration.ofDays(graceDays);
    this.reservationTtl = Duration.ofMinutes(reservationTtlMinutes);
  }

  // ---------------------------------------------------------------------------------------------
  // Subscribe — reserve → charge → activate
  // ---------------------------------------------------------------------------------------------

  @Override
  public SubscriptionDto subscribe(SubscribeRequest req) {
    assertChargeableOutsideTransaction();
    // Phase 1 (tx): validate + reserve a PENDING row. The unique index rejects a concurrent second
    // attempt here, before any money moves.
    Reservation reservation = tx.execute(status -> reserve(req));

    // Phase 2 (NO tx): charge the gateway. No JDBC connection is held across this network call.
    // The reservation is already committed, so a failure here must be resolved rather than left
    // dangling: the partial unique index covers PENDING as well as ACTIVE, so an orphaned PENDING
    // row blocks the user from subscribing again. Resolution depends on how definite the gateway's
    // answer was — an explicit decline is abandoned, a thrown failure is left PENDING. The
    // stale-PENDING reaper sweepStalePendingReservations(int) is the backstop for a hard crash
    // here.
    if (reservation.chargeable()) {
      PaymentResult payment;
      try {
        payment =
            paymentPort.charge(
                reservation.userId(), reservation.toCharge(), reservation.reference());
      } catch (RuntimeException gatewayFailure) {
        // A thrown failure is ambiguous — timeout, reset, open breaker — so the gateway may or may
        // not have captured. The ledger row is marked IN_DOUBT and the reservation is left PENDING,
        // not CANCELLED: CANCELLED is terminal, and cancelling would discard the reference a retry
        // needs to resolve the ambiguity, so the member could be charged a second time.
        // SubscriptionRepository.findStalePendingIds excludes CHARGED and IN_DOUBT for the same
        // reason. Leaving the row PENDING blocks the user until reconciliation clears it, which is
        // the safe side of the trade-off against a double charge.
        paymentLedger.markInDoubt(reservation.reference(), gatewayFailure.toString());
        throw gatewayFailure;
      }
      if (!payment.success()) {
        // An explicit decline IS unambiguous: the gateway is telling us it did not capture.
        paymentLedger.markAbandoned(reservation.reference(), payment.message());
        abandon(reservation.subId());
        throw new PaymentFailedException("Payment was declined");
      }
      // Money has moved. The ledger row is marked CHARGED in its own transaction so it survives a
      // phase-3 rollback. A CHARGED row that has not yet reached APPLIED is durable evidence that
      // this member is owed delivery, so a phase-3 failure leaves a detectable record rather than
      // no trace at all.
      paymentLedger.markCharged(reservation.reference(), payment.transactionRef());
    }

    // Phase 3 (tx): confirm the reservation (PENDING → ACTIVE), mark the payment APPLIED in the
    // SAME transaction (so ledger and subscription can never disagree), and record the outcome.
    try {
      return tx.execute(status -> activateReservation(reservation));
    } catch (RuntimeException applyFailed) {
      subscriptionEvents.chargedNotApplied(
          reservation.reference(), reservation.subId(), applyFailed);
      throw applyFailed;
    }
  }

  /** Roll a PENDING reservation forward to CANCELLED in its own transaction (frees the index). */
  private void abandon(Long subscriptionId) {
    tx.executeWithoutResult(status -> abandonReservation(subscriptionId));
  }

  private Reservation reserve(SubscribeRequest req) {
    if (!appUserRepository.existsById(req.userId())) {
      throw new NotFoundException("User %d is not registered".formatted(req.userId()));
    }
    MembershipPlan plan = planService.requireActivePlan(req.planId());
    MembershipTier tier = tierService.requireTier(req.tierId());

    UserActivity activity = userActivityService.currentActivity(req.userId());
    if (!tierAssignmentService.isEligibleFor(tier, activity)) {
      throw new BusinessRuleException(
          "User %d is not eligible for tier %s".formatted(req.userId(), tier.getCode()));
    }
    // Friendly pre-check; the partial unique index is the actual race-proof guarantee.
    if (subscriptionRepository.existsByUserIdAndStatus(req.userId(), SubscriptionStatus.ACTIVE)) {
      throw new ConflictException(
          "User %d already has an active subscription".formatted(req.userId()));
    }

    // RESUME, don't re-reserve. A crash between charge and activate leaves a PENDING row whose
    // money may already have moved. Reusing the orphan keeps its reference stable, so recordIntent
    // returns ALREADY_CHARGED and we activate what was already paid for instead of charging again.
    Subscription orphan =
        subscriptionRepository
            .findFirstByUserIdAndStatus(req.userId(), SubscriptionStatus.PENDING)
            .orElse(null);
    if (orphan != null) {
      return resumeReservation(orphan, req);
    }

    Instant now = clock.instant();
    // Full-period price is locked onto the subscription (grandfathering / renewal), regardless of
    // any trial applied to the immediate charge.
    BigDecimal price = pricingService.priceFor(plan, tier);
    boolean trial = plan.getTrialDays() > 0;

    BigDecimal toCharge = trial ? BigDecimal.ZERO : price;

    Instant expiresAt =
        trial
            ? now.plus(Duration.ofDays(plan.getTrialDays())) // trial window; converts at trial end
            : now.plus(Duration.ofDays(plan.getDurationDays()));
    Subscription reserved =
        Subscription.pendingReservation(req.userId(), plan, tier, price, now, expiresAt);
    if (trial) {
      reserved.beginTrial(expiresAt);
    }
    // INSERT of the PENDING row; a concurrent reservation for the same user fails the unique index
    // here (surfaced as a ConflictException via the exception handler).
    Subscription saved = subscriptionRepository.save(reserved);

    // Reference is per-RESERVATION, not per (user, plan). It doubles as the gateway idempotency
    // key: a stable "sub-{userId}-{planId}" would make a later resubscribe to the same plan replay
    // the original charge — a free membership for anyone who cancels and comes back. Scoping it to
    // the reservation id keeps it stable across retries of this attempt (which resume the same row,
    // see above) while staying distinct across genuinely new subscriptions.
    String reference = subscribeReference(req.userId(), plan.getId(), saved.getId());

    IntentOutcome intent = IntentOutcome.CHARGE_NOW;
    if (!trial && toCharge.signum() > 0) {
      // Durable intent, committed with the reservation and therefore BEFORE any money moves.
      intent =
          paymentLedger.recordIntent(
              saved.getId(), req.userId(), toCharge, reference, PaymentPurpose.SUBSCRIBE);
    }

    return new Reservation(
        saved.getId(),
        req.userId(),
        toCharge,
        trial,
        reference,
        plan.getId(),
        tier.getId(),
        tier.getName(),
        intent);
  }

  private static String subscribeReference(long userId, Long planId, Long subscriptionId) {
    return "sub-%d-%d-%d".formatted(userId, planId, subscriptionId);
  }

  /**
   * Re-attach to an orphaned PENDING reservation instead of creating a second one.
   *
   * <p>The plan/tier must match: a PENDING row for a <em>different</em> plan is a genuine conflict
   * (the unique index would reject the insert anyway), and silently switching the member onto the
   * plan they asked for last time would be worse than an error.
   */
  private Reservation resumeReservation(Subscription orphan, SubscribeRequest req) {
    if (!orphan.getPlan().getId().equals(req.planId())
        || !orphan.getTier().getId().equals(req.tierId())) {
      throw new ConflictException(
          "User %d has an unresolved reservation for a different plan/tier; it will be released "
                  .formatted(req.userId())
              + "automatically, please retry shortly");
    }
    String reference = subscribeReference(req.userId(), orphan.getPlan().getId(), orphan.getId());
    boolean trial = orphan.getTrialEnd() != null;
    BigDecimal toCharge =
        paymentLedger.findByReference(reference).map(Payment::getAmount).orElse(BigDecimal.ZERO);

    IntentOutcome intent = IntentOutcome.CHARGE_NOW;
    if (!trial && toCharge.signum() > 0) {
      intent =
          paymentLedger.recordIntent(
              orphan.getId(), req.userId(), toCharge, reference, PaymentPurpose.SUBSCRIBE);
    }
    log.info(
        "Resuming orphaned reservation sub={} ref={} intent={}", orphan.getId(), reference, intent);

    return new Reservation(
        orphan.getId(),
        req.userId(),
        toCharge,
        trial,
        reference,
        orphan.getPlan().getId(),
        orphan.getTier().getId(),
        orphan.getTier().getName(),
        intent);
  }

  private SubscriptionDto activateReservation(Reservation r) {
    Subscription sub =
        subscriptionRepository
            .findByIdWithDetails(r.subId())
            .orElseThrow(
                () -> new NotFoundException("Reservation %d vanished".formatted(r.subId())));
    sub.activate();

    BigDecimal charged = r.trial() ? BigDecimal.ZERO : r.toCharge();
    Map<String, Object> detail = new HashMap<>();
    detail.put("planId", r.planId());
    detail.put("tierId", r.tierId());
    detail.put("charged", charged);
    if (r.trial()) {
      detail.put("trial", true);
    }
    if (r.applicable()) {
      paymentLedger.markApplied(r.reference());
    }
    subscriptionEvents.record(sub, EventType.SUBSCRIBED, detail);
    subscriptionEvents.publish(
        sub,
        EventType.SUBSCRIBED,
        r.trial()
            ? "Free trial started for FirstClub %s".formatted(r.tierName())
            : "Welcome to FirstClub %s".formatted(r.tierName()));
    return SubscriptionDto.from(sub);
  }

  private void abandonReservation(Long subscriptionId) {
    subscriptionRepository
        .findByIdWithDetails(subscriptionId)
        .ifPresent(Subscription::abandonReservation); // PENDING → CANCELLED; frees the unique index
  }

  // ---------------------------------------------------------------------------------------------
  // Upgrade — reserve (validate + price) → charge → activate (apply)
  // ---------------------------------------------------------------------------------------------

  @Override
  public SubscriptionDto upgrade(Long subscriptionId, Long targetTierId) {
    assertChargeableOutsideTransaction();
    // Phase 1 (tx): validate eligibility, compute the proration owed, and record the payment
    // intent — durable before any money moves. No subscription mutation yet.
    PendingCharge charge = tx.execute(status -> planUpgrade(subscriptionId, targetTierId));
    if (charge == null) {
      // The ledger says this exact upgrade was already charged and applied. Return current state
      // rather than charging a second time — gateway idempotency dedupes the money, but only this
      // check stops the tier change being applied twice.
      return tx.execute(status -> SubscriptionDto.from(requireActiveSubscription(subscriptionId)));
    }

    // Phase 2 (NO tx): charge the delta. Any failure abandons the intent, mirroring subscribe(), so
    // that a charge always leaves a ledger trace even when the apply never happens. Without that a
    // failed upgrade could take money and record nothing. PendingCharge.chargeable() also skips the
    // gateway when the ledger already knows this reference was captured, so a retry after a failed
    // apply finishes delivery instead of charging twice.
    if (charge.chargeable()) {
      PaymentResult payment;
      try {
        payment = paymentPort.charge(charge.userId(), charge.amount(), charge.reference());
      } catch (RuntimeException gatewayFailure) {
        // A thrown failure is ambiguous, so the ledger row is marked IN_DOUBT, never ABANDONED —
        // same reasoning as the subscribe() path above.
        paymentLedger.markInDoubt(charge.reference(), gatewayFailure.toString());
        throw gatewayFailure;
      }
      if (!payment.success()) {
        paymentLedger.markAbandoned(charge.reference(), payment.message());
        throw new PaymentFailedException("Upgrade payment was declined");
      }
      paymentLedger.markCharged(charge.reference(), payment.transactionRef());
    }

    // Phase 3 (tx): apply the tier change under optimistic locking; a losing racer is rejected.
    // The money has already moved by this point, so a rejection here leaves a CHARGED ledger row
    // and
    // surfaces the discrepancy (subscriptionEvents.chargedNotApplied below) for the reconciler.
    try {
      return tx.execute(status -> applyUpgrade(subscriptionId, targetTierId, charge));
    } catch (RuntimeException applyFailed) {
      if (charge.amount().signum() > 0) {
        subscriptionEvents.chargedNotApplied(charge.reference(), subscriptionId, applyFailed);
      }
      throw applyFailed;
    }
  }

  private PendingCharge planUpgrade(Long subscriptionId, Long targetTierId) {
    Subscription sub = requireActiveSubscription(subscriptionId);
    MembershipTier target = tierService.requireTier(targetTierId);
    if (target.getRank() <= sub.getTier().getRank()) {
      throw new BusinessRuleException(
          "Target tier %s is not an upgrade".formatted(target.getCode()));
    }
    UserActivity activity = userActivityService.currentActivity(sub.getUserId());
    if (!tierAssignmentService.isEligibleFor(target, activity)) {
      throw new BusinessRuleException(
          "User %d has not met the criteria for tier %s"
              .formatted(sub.getUserId(), target.getCode()));
    }
    BigDecimal proration =
        pricingService.proratedUpgradeCharge(
            sub.getPlan(), sub.getTier(), target, clock.instant(), sub.getExpiresAt());
    // The reference must be unique per upgrade ATTEMPT, not per (subscription, tier). A fixed
    // "upgrade-{sub}-{tier}" is permanent for the life of the subscription, so after
    // GOLD → downgrade → upgrade-back-to-GOLD the ledger still holds the original APPLIED row and
    // the second upgrade is told "already done" — 200 with the member still on the lower tier.
    //
    // The event count supplies the monotonic component. Every lifecycle change appends an event, so
    // it advances across the intervening downgrade and gives the retry its own charge, while
    // staying
    // stable within a single attempt, so a failed apply resumes rather than re-charges. (`version`
    // would read more naturally but is not exposed on the entity: it is Hibernate's optimistic-lock
    // counter, not a domain value.)
    long attemptSeq = eventRepository.countBySubscriptionId(sub.getId());
    String reference = "upgrade-%d-%d-%d".formatted(sub.getId(), target.getId(), attemptSeq);
    IntentOutcome intent = IntentOutcome.CHARGE_NOW;
    if (proration.signum() > 0) {
      intent =
          paymentLedger.recordIntent(
              sub.getId(), sub.getUserId(), proration, reference, PaymentPurpose.UPGRADE);
      if (intent == IntentOutcome.ALREADY_DONE) {
        return null; // genuinely applied already — see upgrade()
      }
    }
    // ALREADY_CHARGED falls through with charge-now suppressed: money moved on a previous attempt
    // that failed to apply, so we finish the delivery rather than charging twice or stalling.
    return new PendingCharge(sub.getUserId(), proration, reference, intent);
  }

  private SubscriptionDto applyUpgrade(
      Long subscriptionId, Long targetTierId, PendingCharge charge) {
    Subscription sub = requireActiveSubscription(subscriptionId);
    MembershipTier target = tierService.requireTier(targetTierId);
    // Re-check under the fresh row: if a concurrent upgrade already moved us here, reject cleanly.
    if (target.getRank() <= sub.getTier().getRank()) {
      throw new BusinessRuleException(
          "Target tier %s is not an upgrade".formatted(target.getCode()));
    }
    sub.reprice(pricingService.priceFor(sub.getPlan(), target)); // renewals bill at the new tier
    String from = sub.getTier().getCode();
    sub.changeTier(target);
    if (charge.amount().signum() > 0) {
      paymentLedger.markApplied(charge.reference()); // atomic with the tier change
    }
    subscriptionEvents.record(
        sub,
        EventType.UPGRADED,
        Map.of("from", from, "to", target.getCode(), "proratedCharge", charge.amount()));
    subscriptionEvents.publish(
        sub, EventType.UPGRADED, "Upgraded to %s".formatted(target.getName()));
    return SubscriptionDto.from(sub);
  }

  // ---------------------------------------------------------------------------------------------
  // Non-paying mutations — plain single transactions (no external I/O)
  // ---------------------------------------------------------------------------------------------

  @Transactional
  @Override
  public SubscriptionDto downgrade(Long subscriptionId, Long targetTierId) {
    Subscription sub = requireActiveSubscription(subscriptionId);
    MembershipTier target = tierService.requireTier(targetTierId);
    if (target.getRank() >= sub.getTier().getRank()) {
      throw new BusinessRuleException(
          "Target tier %s is not a downgrade".formatted(target.getCode()));
    }
    String from = sub.getTier().getCode();
    sub.changeTier(target); // downgrade is always allowed — it is the user's choice
    // No mid-cycle refund; the lower price takes effect at renewal (a documented policy choice).
    sub.reprice(pricingService.priceFor(sub.getPlan(), target));
    subscriptionEvents.record(
        sub, EventType.DOWNGRADED, Map.of("from", from, "to", target.getCode()));
    subscriptionEvents.publish(
        sub, EventType.DOWNGRADED, "Moved to %s".formatted(target.getName()));
    return SubscriptionDto.from(sub);
  }

  @Transactional
  @Override
  public SubscriptionDto cancel(Long subscriptionId) {
    Subscription sub = requireActiveSubscription(subscriptionId);
    BigDecimal credit = creditUnusedTime(sub, "CANCEL");
    sub.cancel();
    subscriptionEvents.record(
        sub, EventType.CANCELLED, credit.signum() > 0 ? Map.of("credit", credit) : Map.of());
    subscriptionEvents.publish(sub, EventType.CANCELLED, "Your membership has been cancelled");
    return SubscriptionDto.from(sub);
  }

  @Transactional
  @Override
  public SubscriptionDto refund(Long subscriptionId) {
    // Refund / chargeback revokes access. A real adapter would also call paymentPort.refund(...)
    // outside this transaction; a chargeback can arrive weeks later, so in production this must
    // handle non-ACTIVE states too.
    Subscription sub = requireActiveSubscription(subscriptionId);
    BigDecimal credit = creditUnusedTime(sub, "REFUND");
    // Reverse the ledger in the same transaction. The ledger is the system of record for money, so
    // a payment left APPLIED after a refund makes every revenue figure built on it wrong.
    paymentLedger.appliedReferenceFor(sub.getId()).ifPresent(paymentLedger::markRefunded);
    sub.cancel();
    subscriptionEvents.record(
        sub, EventType.REFUNDED, credit.signum() > 0 ? Map.of("credit", credit) : Map.of());
    subscriptionEvents.publish(
        sub, EventType.REFUNDED, "Your membership was refunded and access revoked");
    return SubscriptionDto.from(sub);
  }

  // ---------------------------------------------------------------------------------------------
  // Reads — genuinely read-only. Lazy expiry returns the correct view WITHOUT writing; the
  // scheduled sweep owns the persistent EXPIRED transition. A GET must never mutate.
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  @Override
  public MembershipView getMembership(long userId) {
    Subscription sub = subscriptionRepository.findActiveByUserId(userId).orElse(null);
    if (sub == null || pastGrace(sub)) {
      return MembershipView.inactive(
          userId); // past grace → treated as inactive; sweeper persists it
    }
    return MembershipView.of(sub, benefitResolver.benefitsForTier(sub.getTier().getId()));
  }

  /**
   * Recompute the user's tier from current activity and auto-upgrade if they now qualify for more.
   * Never auto-downgrades within a paid period: benefits earned are not clawed back mid-cycle, and
   * a downgrade is always the member's own choice.
   */
  @Transactional
  @Override
  public MembershipView reevaluateTier(long userId) {
    Subscription sub = subscriptionRepository.findActiveByUserId(userId).orElse(null);
    if (sub == null || pastGrace(sub)) {
      return MembershipView.inactive(
          userId); // no write on the read path; the sweeper persists expiry
    }
    UserActivity activity = userActivityService.currentActivity(userId);
    MembershipTier best = tierAssignmentService.highestQualifyingTier(activity);
    if (best.getRank() > sub.getTier().getRank()) {
      String from = sub.getTier().getCode();
      sub.changeTier(best);
      subscriptionEvents.record(
          sub, EventType.AUTO_UPGRADED, Map.of("from", from, "to", best.getCode()));
      subscriptionEvents.publish(
          sub, EventType.AUTO_UPGRADED, "You've been upgraded to %s".formatted(best.getName()));
    }
    return MembershipView.of(sub, benefitResolver.benefitsForTier(sub.getTier().getId()));
  }

  /** Bulk-normalize expired subscriptions past their grace window. Bounded batch; index-backed. */
  @Transactional
  @Override
  public int sweepExpired(int batchSize) {
    // Only expire subscriptions whose grace window has also elapsed.
    Instant cutoff = clock.instant().minus(grace);
    List<Long> ids = subscriptionRepository.findExpiredIds(cutoff, PageRequest.of(0, batchSize));
    if (ids.isEmpty()) {
      return 0;
    }
    return subscriptionRepository.markExpiredByIds(ids);
  }

  /**
   * Backstop for the reserve → charge → activate flow: cancel PENDING reservations older than the
   * reservation TTL. The subscribe path already abandons a reservation on a declined or thrown
   * charge; this only catches the rare hard crash (process death) between reserving and resolving,
   * which would otherwise leave a PENDING row blocking the user in the PENDING+ACTIVE unique index.
   */
  @Transactional
  @Override
  public int sweepStalePendingReservations(int batchSize) {
    Instant cutoff = clock.instant().minus(reservationTtl);
    List<Long> ids =
        subscriptionRepository.findStalePendingIds(cutoff, PageRequest.of(0, batchSize));
    if (ids.isEmpty()) {
      return 0;
    }
    return subscriptionRepository.cancelStalePendingByIds(ids);
  }

  @Transactional(readOnly = true)
  @Override
  public List<CreditNoteDto> creditNotes(long userId, int page, int size) {
    return creditNoteRepository
        .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
        .stream()
        .map(CreditNoteDto::from)
        .toList();
  }

  /**
   * Whether {@code principalName} (the JWT subject, i.e. the user id as a string) owns this
   * subscription. Backs the {@code @PreAuthorize} on every subscription mutation: without it, the
   * path-addressed endpoints (upgrade / downgrade / cancel / refund) authorise on "is
   * authenticated" alone, which lets any token holder mutate any member's subscription.
   */
  @Transactional(readOnly = true)
  @Override
  public boolean isOwner(Long subscriptionId, String principalName) {
    if (subscriptionId == null || principalName == null) {
      return false;
    }
    return subscriptionRepository
        .findById(subscriptionId)
        .map(s -> String.valueOf(s.getUserId()).equals(principalName))
        .orElse(false);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** True once the subscription is past its paid-through date AND its grace window. */
  private boolean pastGrace(Subscription sub) {
    return sub.getExpiresAt().isBefore(clock.instant().minus(grace));
  }

  /**
   * Issue a credit note for the unused portion of the current period. Returns the credited amount
   * (zero if none).
   *
   * <p>The basis is <b>money actually collected</b> — {@code PaymentRepository.collectedFor}, which
   * sums APPLIED ledger rows — not {@code sub.getPricePaid()}, which holds the list price so
   * renewals grandfather correctly. Summing the ledger makes an unconverted trial credit zero by
   * construction, rather than by a status check someone has to remember.
   */
  private BigDecimal creditUnusedTime(Subscription sub, String reason) {
    BigDecimal collected = paymentLedger.collectedFor(sub.getId());
    if (collected.signum() <= 0) {
      return BigDecimal.ZERO; // nothing was ever collected — nothing to give back
    }
    BigDecimal credit =
        pricingService.proratedRefundCredit(
            sub.getPlan(), collected, clock.instant(), sub.getExpiresAt());
    if (credit.signum() > 0) {
      creditNoteRepository.save(new CreditNote(sub.getId(), sub.getUserId(), credit, reason));
    }
    return credit;
  }

  private Subscription requireActiveSubscription(Long subscriptionId) {
    Subscription sub =
        subscriptionRepository
            .findByIdWithDetails(subscriptionId)
            .orElseThrow(
                () -> new NotFoundException("Subscription %d not found".formatted(subscriptionId)));
    if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
      throw new ConflictException(
          "Subscription %d is %s, not ACTIVE".formatted(subscriptionId, sub.getStatus()));
    }
    return sub;
  }

  /**
   * A reserved (PENDING) subscription awaiting a charge, carried across the transaction boundary.
   */
  private record Reservation(
      Long subId,
      long userId,
      BigDecimal toCharge,
      boolean trial,
      String reference,
      Long planId,
      Long tierId,
      String tierName,
      IntentOutcome intent) {

    /**
     * Whether this reservation still needs a gateway charge. False for a trial (nothing is owed)
     * and for a resumed reservation whose money already moved. The {@code intent} term reflects
     * what the ledger already knows: on an {@link IntentOutcome#ALREADY_CHARGED} resume the
     * reference was already captured, so retrying a half-finished subscribe skips the gateway and
     * applies the existing payment instead of charging a second time.
     */
    boolean chargeable() {
      return !trial && toCharge.signum() > 0 && intent.shouldCallGateway();
    }

    /**
     * Whether the activate phase ({@link #activateReservation}) owes this reference a {@link
     * PaymentLedger#markApplied}. This differs from {@link #chargeable()}: a resumed {@link
     * IntentOutcome#ALREADY_CHARGED} reservation skips the gateway yet still must reach APPLIED,
     * because its money already moved. Gating the apply on "did we call the gateway" would strand
     * that payment at CHARGED forever — alerting on a membership that was in fact delivered, and
     * reporting zero collected when it is later cancelled, so the member would lose credit for
     * money they really paid.
     */
    boolean applicable() {
      return !trial && toCharge.signum() > 0 && intent.shouldApply();
    }
  }
}
