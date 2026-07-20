package com.firstclub.membership.repository;

import com.firstclub.membership.model.UserMonthlySpend;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserMonthlySpendRepository
    extends JpaRepository<UserMonthlySpend, UserMonthlySpend.Key> {

  Optional<UserMonthlySpend> findByUserIdAndYearMonth(Long userId, String yearMonth);

  /**
   * Rebuild one month's bucket from the order events that fall in it. This is what makes the bucket
   * a derived cache rather than a caller-asserted figure: the amount is always {@code SUM(amount)}
   * over {@code user_order_event} for that user and month, so a re-delivered order (a no-op on the
   * events table) or a refund (a lower re-assertion) both land correctly. The month is matched on
   * {@code occurred_at} in UTC, the same window the read path resolves.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          """
            insert into user_monthly_spend (user_id, year_month, amount, updated_at)
            select :userId, :yearMonth, coalesce(sum(amount), 0), now()
            from user_order_event
            where user_id = :userId
              and to_char((occurred_at at time zone 'utc'), 'YYYY-MM') = :yearMonth
            on conflict (user_id, year_month) do update set
                amount     = excluded.amount,
                updated_at = now()
            """,
      nativeQuery = true)
  void recomputeMonthFromEvents(@Param("userId") long userId, @Param("yearMonth") String yearMonth);
}
