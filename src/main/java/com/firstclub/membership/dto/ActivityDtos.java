package com.firstclub.membership.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ActivityDtos {

  private ActivityDtos() {}

  /**
   * Feed one order event, the way a grocery order stream drives the system.
   *
   * <p>{@code orderId} is the idempotency key: re-delivering an order corrects its row instead of
   * double-counting, and a refund is the same order re-sent with a lower amount. The month a spend
   * counts toward comes from {@code occurredAt}, so the caller's clock decides the window, not the
   * server's clock at receipt. The order's amount feeds {@code user_order_event}, and the month's
   * {@code user_monthly_spend} bucket is then recomputed as a SUM over the events.
   *
   * <p>{@code orderCount} and {@code cohorts} are lifetime snapshots carried alongside the order.
   */
  public record ActivityUpdateRequest(
      @NotNull @PositiveOrZero Integer orderCount,
      @NotNull Long orderId,
      @NotNull @PositiveOrZero BigDecimal orderAmount,
      @NotNull Instant occurredAt,
      List<String> cohorts) {}
}
