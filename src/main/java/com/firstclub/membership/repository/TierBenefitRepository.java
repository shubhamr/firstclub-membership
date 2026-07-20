package com.firstclub.membership.repository;

import com.firstclub.membership.model.TierBenefit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TierBenefitRepository extends JpaRepository<TierBenefit, Long> {

  /**
   * Fetch-join the benefit so resolving a tier's benefits is a single query — no N+1 when reading
   * the (benefit code, type, name) for each mapping row.
   */
  @Query(
      """
            select tb from TierBenefit tb
            join fetch tb.benefit b
            where tb.tierId = :tierId and tb.active = true and b.active = true
            """)
  List<TierBenefit> findActiveByTierId(@Param("tierId") Long tierId);
}
