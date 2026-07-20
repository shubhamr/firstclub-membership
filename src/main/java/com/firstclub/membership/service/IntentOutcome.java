package com.firstclub.membership.service;

/**
 * The state of a payment reference after {@link PaymentLedger#recordIntent} — tells the caller
 * whether to charge, to (re)apply, or to stop.
 *
 * <p>A payment for one reference passes through three stages: no money taken, money taken but the
 * subscription change not yet committed, and fully finished (applied or refunded). All three are
 * named here because the middle stage is real and recoverable: a charge can succeed and then the
 * apply transaction fail, leaving the member charged but not delivered. Collapsing "done / not
 * done" into a boolean would fold that middle stage into "done", so every retry returns early and
 * the member stays charged-and-undelivered.
 */
public enum IntentOutcome {

  /** No money has moved for this reference — call the gateway, then apply. */
  CHARGE_NOW,

  /**
   * Money was captured but the subscription change never committed. Skip the gateway and run the
   * apply phase anyway; each apply is independently idempotent and no-ops if the work is already
   * done — the compare-and-set in {@code RenewalServiceImpl.applyRenewalOutcome}, the state guard
   * in trial conversion, and the tier re-check in upgrade.
   */
  ALREADY_CHARGED,

  /** Applied or refunded — genuinely finished. Do nothing. */
  ALREADY_DONE;

  public boolean shouldCallGateway() {
    return this == CHARGE_NOW;
  }

  /** True when the caller should run its apply phase — including the recovery case. */
  public boolean shouldApply() {
    return this != ALREADY_DONE;
  }
}
