package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.event.MembershipLifecycleEvent;
import com.firstclub.membership.model.EventType;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Pins the observer wiring: a subscribe publishes exactly one {@code SUBSCRIBED} lifecycle event
 * for the subscribing user.
 */
@RecordApplicationEvents
class DomainEventsIntegrationTest extends AbstractIntegrationTest {

  @Autowired ApplicationEvents applicationEvents;
  @Autowired SubscriptionService subscriptionService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(3001L);
  }

  @Test
  void subscribe_publishesLifecycleEvent() {
    long userId = 3001L;
    Long planId = planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
    Long silver = tierRepository.findByCode("SILVER").orElseThrow().getId();

    subscriptionService.subscribe(new SubscribeRequest(userId, planId, silver));

    long subscribed =
        applicationEvents.stream(MembershipLifecycleEvent.class)
            .filter(e -> e.userId() == userId && e.type() == EventType.SUBSCRIBED)
            .count();
    assertThat(subscribed).isEqualTo(1);
  }
}
