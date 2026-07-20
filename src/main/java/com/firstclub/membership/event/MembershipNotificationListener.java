package com.firstclub.membership.event;

import com.firstclub.membership.gateway.NotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the user-facing notification for a membership change, driven by the lifecycle event rather
 * than a call baked into the service.
 *
 * <p>Listening on {@code AFTER_COMMIT} means a rolled-back transaction never notifies. The port
 * itself is {@code @Async}, so delivery stays off the request thread.
 */
@Component
@RequiredArgsConstructor
public class MembershipNotificationListener {

  private final NotificationPort notificationPort;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMembershipChange(MembershipLifecycleEvent event) {
    notificationPort.notifyMembershipChange(event.userId(), event.type().name(), event.message());
  }
}
