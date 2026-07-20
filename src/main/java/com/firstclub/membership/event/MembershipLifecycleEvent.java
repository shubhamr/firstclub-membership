package com.firstclub.membership.event;

import com.firstclub.membership.model.EventType;

/**
 * Domain event published on every subscription lifecycle change. Decouples side effects
 * (notification, and any future audit/analytics/webhooks) from the transactional command —
 * listeners subscribe instead of the service calling them directly.
 */
public record MembershipLifecycleEvent(
    long userId, Long subscriptionId, EventType type, String message) {}
