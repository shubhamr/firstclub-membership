package com.firstclub.membership.service;

import com.firstclub.membership.repository.UserMonthlySpendRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds a user's monthly spend bucket from the order events, serialized per user.
 *
 * <p>The recompute upserts {@code SUM(events)} with {@code ON CONFLICT DO UPDATE}, which freezes
 * the sum from each statement's snapshot. Two concurrent orders for one user could therefore let a
 * stale recompute clobber a fresher one and under-count the bucket that tier eligibility reads. A
 * per-user advisory lock taken before the recompute serializes them, so the {@code SUM} always runs
 * after any concurrent order has committed.
 */
@Component
@RequiredArgsConstructor
public class MonthlySpendRecalculator {

  @PersistenceContext private EntityManager entityManager;

  private final UserMonthlySpendRepository monthlySpendRepository;

  @Transactional
  public void recompute(long userId, String yearMonth) {
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(:key)")
        .setParameter("key", userId)
        .getResultList();
    monthlySpendRepository.recomputeMonthFromEvents(userId, yearMonth);
  }
}
