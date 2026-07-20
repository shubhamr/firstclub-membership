package com.firstclub.membership.model;

/** Why a charge was made — kept on the ledger row so revenue can be attributed without a join. */
public enum PaymentPurpose {
  SUBSCRIBE,
  UPGRADE,
  RENEWAL,
  TRIAL_CONVERSION
}
