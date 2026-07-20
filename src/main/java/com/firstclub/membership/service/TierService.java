package com.firstclub.membership.service;

import com.firstclub.membership.dto.TierDto;
import com.firstclub.membership.model.MembershipTier;
import java.util.List;

/** Read access to tiers and the benefits they unlock. */
public interface TierService {

  /** Active tiers with resolved benefits (cached). */
  List<TierDto> listTiers();

  /** Load an active tier for the subscription flow, or fail if missing/inactive. */
  MembershipTier requireTier(Long tierId);
}
