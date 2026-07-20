package com.firstclub.membership.repository;

import com.firstclub.membership.model.MembershipTier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierRepository extends JpaRepository<MembershipTier, Long> {

  List<MembershipTier> findByActiveTrueOrderByRankAsc();

  Optional<MembershipTier> findByCode(String code);
}
