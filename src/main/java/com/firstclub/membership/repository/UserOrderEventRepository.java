package com.firstclub.membership.repository;

import com.firstclub.membership.model.UserOrderEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserOrderEventRepository extends JpaRepository<UserOrderEvent, Long> {

  /**
   * Record one order's spend. Keyed on {@code order_id}, so a re-delivered order corrects the row
   * rather than adding a second one. A refund or adjustment is the same call with a lower amount.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          """
            insert into user_order_event (order_id, user_id, amount, occurred_at, received_at)
            values (:orderId, :userId, :amount, :occurredAt, now())
            on conflict (order_id) do update set
                amount      = excluded.amount,
                occurred_at = excluded.occurred_at
            """,
      nativeQuery = true)
  void record(
      @Param("orderId") long orderId,
      @Param("userId") long userId,
      @Param("amount") BigDecimal amount,
      @Param("occurredAt") Instant occurredAt);

  /**
   * Spend for a user over a half-open instant range {@code [from, to)}. This is what the monthly
   * bucket structurally could not express: a rolling window is just a different range here. Returns
   * zero rather than null when the user has no events in the range.
   */
  @Query(
      value =
          """
            select coalesce(sum(amount), 0) from user_order_event
            where user_id = :userId and occurred_at >= :from and occurred_at < :to
            """,
      nativeQuery = true)
  BigDecimal sumForUserBetween(
      @Param("userId") long userId, @Param("from") Instant from, @Param("to") Instant to);
}
