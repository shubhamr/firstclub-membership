package com.firstclub.membership.service;

import com.firstclub.membership.dto.PlanDto;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.MembershipPlan;
import com.firstclub.membership.repository.PlanRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Read path for plans. The catalog is small and rarely mutated, so it is cached; this is the
 * high-read surface hit on every checkout/landing render.
 */
@Service
@RequiredArgsConstructor
public class PlanServiceImpl implements PlanService {

  private final PlanRepository repository;

  @Cacheable("plans")
  @Override
  public List<PlanDto> listActivePlans() {
    return repository.findByActiveTrueOrderByPriceAsc().stream().map(PlanDto::from).toList();
  }

  /** Loads the entity for the subscription flow (not cached — used transactionally). */
  @Override
  public MembershipPlan requireActivePlan(Long planId) {
    MembershipPlan plan =
        repository
            .findById(planId)
            .orElseThrow(() -> new NotFoundException("Plan %d not found".formatted(planId)));
    if (!plan.isActive()) {
      throw new NotFoundException("Plan %d is not active".formatted(planId));
    }
    return plan;
  }
}
