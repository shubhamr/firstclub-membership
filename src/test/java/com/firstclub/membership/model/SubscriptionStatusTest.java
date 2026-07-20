package com.firstclub.membership.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the lifecycle transition table: which statuses may follow which, and which permit
 * modification. Pure unit tests, no Spring context.
 */
class SubscriptionStatusTest {

  @Test
  void active_canCancelExpireAndBeModified() {
    assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
    assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.EXPIRED)).isTrue();
    assertThat(SubscriptionStatus.ACTIVE.isModifiable()).isTrue();
  }

  @Test
  void terminalStates_areDeadEndsAndImmutable() {
    assertThat(SubscriptionStatus.CANCELLED.allowedTransitions()).isEmpty();
    assertThat(SubscriptionStatus.EXPIRED.allowedTransitions()).isEmpty();
    assertThat(SubscriptionStatus.CANCELLED.isModifiable()).isFalse();
    assertThat(SubscriptionStatus.CANCELLED.canTransitionTo(SubscriptionStatus.ACTIVE)).isFalse();
  }

  @Test
  void pending_canActivateOrCancelButNotChangeTier() {
    assertThat(SubscriptionStatus.PENDING.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
    assertThat(SubscriptionStatus.PENDING.canTransitionTo(SubscriptionStatus.CANCELLED)).isTrue();
    assertThat(SubscriptionStatus.PENDING.isModifiable()).isFalse();
  }
}
