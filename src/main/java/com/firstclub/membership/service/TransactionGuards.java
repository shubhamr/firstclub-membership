package com.firstclub.membership.service;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Assertions about the ambient transaction state. Static because the check reads a thread-bound
 * value and holds nothing of its own.
 */
final class TransactionGuards {

  private TransactionGuards() {}

  /**
   * Guards the reserve → charge → activate split.
   *
   * <p>The phases must be separate transactions with the gateway charged between them. Under an
   * ambient {@code @Transactional} the TransactionTemplate's default REQUIRED propagation would
   * join it, collapsing the phases into one and pinning a JDBC connection across the charge. Fail
   * loudly rather than silently.
   */
  static void assertChargeableOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "A paying subscription operation must run OUTSIDE a database transaction (reserve → charge"
              + " → activate). An ambient transaction was found — did a caller add @Transactional?");
    }
  }
}
