package com.firstclub.membership.service;

import com.firstclub.membership.event.MembershipLifecycleEvent;
import com.firstclub.membership.model.EventType;
import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionEvent;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * How a lifecycle change is recorded: an audit row, a domain event for listeners, and the alert for
 * the one outcome that has no good ending.
 *
 * <p>Shared by every service that mutates a subscription, so the audit trail and the notification
 * path cannot drift apart between them.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> These methods join the caller's transaction —
 * the one its {@code TransactionTemplate} opened — because the audit row and the state change they
 * describe must commit together or not at all. {@code REQUIRES_NEW} here would let an event survive
 * a rolled-back mutation, leaving the log asserting something that never happened.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEvents {

  private final SubscriptionEventRepository eventRepository;
  private final ApplicationEventPublisher events;
  private final MeterRegistry metrics;

  /** Append an audit row for a lifecycle change. */
  public void record(Subscription sub, EventType type, Map<String, Object> detail) {
    eventRepository.save(new SubscriptionEvent(sub.getId(), sub.getUserId(), type, detail));
  }

  /** Publish for the after-commit listeners (notifications, metrics). */
  public void publish(Subscription sub, EventType type, String message) {
    events.publishEvent(new MembershipLifecycleEvent(sub.getUserId(), sub.getId(), type, message));
  }

  /**
   * The money moved but the state change did not commit. Deliberately not swallowed: the ledger row
   * stays {@code CHARGED} for the reconciler to find, and the counter makes it alertable. A silent
   * catch here would be strictly worse than the exception.
   */
  public void chargedNotApplied(String reference, Long subscriptionId, RuntimeException cause) {
    log.error(
        "CHARGED-NOT-APPLIED ref={} sub={} — money moved, state did not. Left for reconciliation.",
        reference,
        subscriptionId,
        cause);
    metrics.counter("membership.payment.charged_not_applied").increment();
  }
}
