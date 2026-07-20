package com.firstclub.membership.service;

import com.firstclub.membership.dto.BenefitAdminDtos.AssignBenefitRequest;
import com.firstclub.membership.dto.BenefitAdminDtos.BenefitDto;
import com.firstclub.membership.dto.BenefitAdminDtos.CreateBenefitRequest;

/** Runtime configuration of benefits and per-tier perks. */
public interface BenefitAdminService {

  BenefitDto createBenefit(CreateBenefitRequest req);

  /** Assign or re-tune a benefit on a tier; evicts the affected caches. */
  void assignBenefitToTier(AssignBenefitRequest req);

  /**
   * Remove a benefit from a tier and evict the affected caches. The perk stops appearing on the
   * tier; the benefit itself stays in the catalog. This is a soft delete via the mapping's {@code
   * active} flag rather than a physical row removal, matching how the rest of the model retires
   * rows by state — {@link #assignBenefitToTier} reactivates the same mapping if the benefit is
   * later re-attached. Throws {@code NotFoundException} when the benefit code is unknown or is not
   * currently assigned to the tier.
   */
  void unassignBenefitFromTier(Long tierId, String benefitCode);
}
