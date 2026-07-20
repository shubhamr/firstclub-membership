package com.firstclub.membership.service;

import com.firstclub.membership.dto.PlanDto;
import com.firstclub.membership.model.MembershipPlan;
import java.util.List;

/** Read access to membership plans. */
public interface PlanService {

  /** Active plans for the catalog (cached). */
  List<PlanDto> listActivePlans();

  /** Load an active plan for the subscription flow, or fail if missing/inactive. */
  MembershipPlan requireActivePlan(Long planId);
}
