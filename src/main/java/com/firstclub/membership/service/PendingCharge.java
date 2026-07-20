package com.firstclub.membership.service;

import java.math.BigDecimal;

/**
 * A validated charge to make outside the transaction, then apply. Carried across the transaction
 * boundary by every paying operation that is not a first subscribe.
 */
record PendingCharge(long userId, BigDecimal amount, String reference, IntentOutcome intent) {

  /** Skip the gateway when the ledger already knows this reference was captured. */
  boolean chargeable() {
    return amount.signum() > 0 && intent.shouldCallGateway();
  }
}
