package com.firstclub.membership.repository;

import com.firstclub.membership.model.UserOrderStats;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserOrderStatsRepository extends JpaRepository<UserOrderStats, Long> {

  Optional<UserOrderStats> findByUserId(Long userId);

  /**
   * Race-safe upsert of a user's activity snapshot. {@code ON CONFLICT} means concurrent order
   * events for the same user can neither lose an update nor collide on insert. Runs in its own
   * transaction so the snapshot is durable before tier re-evaluation is retried independently.
   *
   * <p>Spend is not written here — it is per-month, in {@link UserMonthlySpendRepository#upsert}.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          """
            insert into user_order_stats (user_id, order_count, cohorts, updated_at)
            values (:userId, :orderCount, cast(:cohorts as jsonb), now())
            on conflict (user_id) do update set
                order_count = excluded.order_count,
                cohorts     = excluded.cohorts,
                updated_at  = now()
            """,
      nativeQuery = true)
  void upsert(
      @Param("userId") long userId,
      @Param("orderCount") int orderCount,
      @Param("cohorts") String cohortsJson);
}
