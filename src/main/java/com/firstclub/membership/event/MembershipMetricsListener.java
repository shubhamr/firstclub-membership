package com.firstclub.membership.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Membership metrics, driven by the same lifecycle event as notifications. Counts only committed
 * events, recorded in the meter registry as {@code membership.lifecycle} tagged by event type,
 * which makes business signals such as a spike in CANCELLED or REFUNDED observable. The counters
 * are kept in-process; exposing them over HTTP (the actuator metrics endpoint) or to a scraping
 * registry is a configuration change that needs no code.
 */
@Component
@RequiredArgsConstructor
public class MembershipMetricsListener {

  private final MeterRegistry registry;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMembershipChange(MembershipLifecycleEvent event) {
    registry.counter("membership.lifecycle", "type", event.type().name()).increment();
  }
}
