package com.firstclub.membership.service;

import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.model.UserActivity;

/** Decides which tier a user qualifies for, given their activity (rule engine). */
public interface TierAssignmentService {

  /** The richest tier the user currently qualifies for; falls back to the base tier. */
  MembershipTier highestQualifyingTier(UserActivity activity);

  /** Whether a user may hold a specific tier (gates manual upgrades). */
  boolean isEligibleFor(MembershipTier tier, UserActivity activity);
}
