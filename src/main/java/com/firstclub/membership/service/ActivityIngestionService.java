package com.firstclub.membership.service;

import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.dto.MembershipView;

/** Write side of user activity: persist the snapshot and re-evaluate the user's tier. */
public interface ActivityIngestionService {

  MembershipView recordActivity(long userId, ActivityUpdateRequest req);
}
