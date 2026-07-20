package com.firstclub.membership.service;

import com.firstclub.membership.model.UserActivity;

/** Read side of user activity, consumed by the tier engine and subscribe flow. */
public interface UserActivityService {

  UserActivity currentActivity(long userId);
}
