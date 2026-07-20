package com.firstclub.membership.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Subscription lifecycle state. Each state owns the legal transitions out of it and whether the
 * subscription is mutable in that state, so the lifecycle rules live in one place instead of
 * scattered {@code if (status == …)} checks. {@link Subscription} delegates its transitions here.
 */
public enum SubscriptionStatus {
  PENDING {
    @Override
    public Set<SubscriptionStatus> allowedTransitions() {
      return EnumSet.of(ACTIVE, CANCELLED);
    }

    @Override
    public boolean isModifiable() {
      return false;
    }
  },
  ACTIVE {
    @Override
    public Set<SubscriptionStatus> allowedTransitions() {
      return EnumSet.of(CANCELLED, EXPIRED);
    }

    @Override
    public boolean isModifiable() {
      return true;
    }
  },
  CANCELLED {
    @Override
    public Set<SubscriptionStatus> allowedTransitions() {
      return EnumSet.noneOf(SubscriptionStatus.class); // terminal
    }

    @Override
    public boolean isModifiable() {
      return false;
    }
  },
  EXPIRED {
    @Override
    public Set<SubscriptionStatus> allowedTransitions() {
      return EnumSet.noneOf(SubscriptionStatus.class); // terminal
    }

    @Override
    public boolean isModifiable() {
      return false;
    }
  };

  /** States reachable from this one. */
  public abstract Set<SubscriptionStatus> allowedTransitions();

  /** Whether tier changes (upgrade/downgrade) are permitted while in this state. */
  public abstract boolean isModifiable();

  public boolean canTransitionTo(SubscriptionStatus target) {
    return allowedTransitions().contains(target);
  }
}
