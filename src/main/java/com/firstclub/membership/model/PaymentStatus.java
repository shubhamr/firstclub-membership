package com.firstclub.membership.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a single charge, modelled as a State machine like {@link SubscriptionStatus}.
 *
 * <p>{@link #CHARGED} and {@link #APPLIED} are the states that matter operationally: a row sitting
 * in {@code CHARGED} means money moved but the corresponding subscription change did not commit.
 * {@code APPLIED} is written in the same transaction as that change, so the pair cannot drift.
 */
public enum PaymentStatus {
  /** About to call the gateway. Written and committed <em>before</em> any money moves. */
  INTENDED {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.of(CHARGED, ABANDONED, IN_DOUBT);
    }
  },
  /**
   * The gateway call failed in a way that does <em>not</em> tell us whether money moved — a read
   * timeout, a connection reset, or an open circuit breaker.
   *
   * <p>Distinct from {@link #ABANDONED}, which asserts no money moved. Recording an ambiguous
   * failure as ABANDONED hides it — reconciliation only scans CHARGED, so a charge that was
   * captured but timed out is written off as a non-event and the member pays twice on retry.
   *
   * <p>Resolve by re-calling the gateway with the same {@code reference} (its idempotency key): the
   * retry replays the original outcome if a capture happened, and charges if it didn't. References
   * must therefore stay stable across retries.
   */
  IN_DOUBT {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.of(CHARGED, ABANDONED, INTENDED);
    }
  },
  /** The gateway approved. Money has moved; delivery is still owed. */
  CHARGED {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.of(APPLIED, REFUNDED);
    }
  },
  /** The subscription change committed. Terminal, and the only happy ending. */
  APPLIED {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.of(REFUNDED);
    }
  },
  /**
   * Explicitly declined by the gateway — money definitively did not move. Reserved for an
   * unambiguous {@code success == false}; anything thrown is {@link #IN_DOUBT}.
   *
   * <p>Re-armable to {@link #INTENDED}: dunning retries reuse the same period-scoped reference for
   * what is logically the same charge.
   */
  ABANDONED {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.of(INTENDED);
    }
  },
  /** Reversed. Terminal. */
  REFUNDED {
    @Override
    public Set<PaymentStatus> allowedTransitions() {
      return EnumSet.noneOf(PaymentStatus.class);
    }
  };

  public abstract Set<PaymentStatus> allowedTransitions();

  public boolean canTransitionTo(PaymentStatus target) {
    return allowedTransitions().contains(target);
  }
}
