package com.firstclub.membership.gateway;

/** Port for user-facing notifications on membership lifecycle changes. */
public interface NotificationPort {

  void notifyMembershipChange(long userId, String event, String message);
}
