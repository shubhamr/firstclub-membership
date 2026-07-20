package com.firstclub.membership.gateway.adapter;

import com.firstclub.membership.gateway.NotificationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget notifications on the bounded {@code notificationExecutor} (see AsyncConfig). Runs
 * off the request thread, after commit, so a slow or failing provider can't delay the request or
 * roll back the subscription. A real adapter would add a timeout, circuit breaker and outbox.
 */
@Component
@Slf4j
public class AsyncNotificationAdapter implements NotificationPort {

  @Override
  @Async("notificationExecutor")
  public void notifyMembershipChange(long userId, String event, String message) {
    // Stub: a real provider call goes here.
    log.info("[notify] user={} event={} message={}", userId, event, message);
  }
}
