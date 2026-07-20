package com.firstclub.membership.repository;

import com.firstclub.membership.model.MembershipPlan;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<MembershipPlan, Long> {

  List<MembershipPlan> findByActiveTrueOrderByPriceAsc();
}
