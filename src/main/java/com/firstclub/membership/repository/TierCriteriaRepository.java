package com.firstclub.membership.repository;

import com.firstclub.membership.model.TierCriteria;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierCriteriaRepository extends JpaRepository<TierCriteria, Long> {

  Optional<TierCriteria> findByTierId(Long tierId);

  List<TierCriteria> findByTierIdIn(List<Long> tierIds);
}
