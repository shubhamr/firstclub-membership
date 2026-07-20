package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.model.UserActivity;
import com.firstclub.membership.repository.UserOrderEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the per-order spend path: the monthly bucket is derived from {@code user_order_event} by
 * summation, so it inherits the properties a caller-asserted total could not have.
 *
 * <p>Each case is a guarantee the pre-aggregated design could not make:
 *
 * <ul>
 *   <li>a re-delivered order does not double-count (idempotent on {@code order_id});
 *   <li>a refund lowers the month it belongs to (spend is correctable, not write-once);
 *   <li>an order counts toward the month it happened in, not the month it was received in;
 *   <li>spend is queryable over an arbitrary range, which a monthly key cannot express.
 * </ul>
 */
class OrderEventSpendIntegrationTest extends AbstractIntegrationTest {

  @Autowired ActivityIngestionService ingestion;
  @Autowired UserActivityService userActivityService;
  @Autowired UserOrderEventRepository orderEventRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(8101L);
    seedUser(8102L);
    seedUser(8103L);
    seedUser(8104L);
    seedUser(8105L);
  }

  private static ActivityUpdateRequest order(long orderId, String amount, Instant occurredAt) {
    return new ActivityUpdateRequest(1, orderId, new BigDecimal(amount), occurredAt, List.of());
  }

  private static Instant dayInCurrentMonth(int day) {
    return YearMonth.now(ZoneOffset.UTC).atDay(day).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private BigDecimal currentMonthSpend(long userId) {
    return userActivityService.currentActivity(userId).monthlySpend();
  }

  @Test
  void perOrderSpend_sumsIntoTheMonthBucket() {
    long userId = 8101L;
    ingestion.recordActivity(userId, order(910101L, "1200.00", dayInCurrentMonth(2)));
    ingestion.recordActivity(userId, order(910102L, "800.00", dayInCurrentMonth(5)));

    assertThat(currentMonthSpend(userId)).isEqualByComparingTo("2000.00");
  }

  @Test
  void redeliveredOrder_doesNotDoubleCount() {
    long userId = 8102L;
    ActivityUpdateRequest sameOrder = order(910201L, "5000.00", dayInCurrentMonth(3));

    ingestion.recordActivity(userId, sameOrder);
    ingestion.recordActivity(userId, sameOrder); // at-least-once delivery replays it

    assertThat(currentMonthSpend(userId))
        .as("order_id is the key, so the replay is a no-op")
        .isEqualByComparingTo("5000.00");
  }

  @Test
  void refund_lowersTheMonthItBelongsTo() {
    long userId = 8103L;
    long orderId = 910301L;
    ingestion.recordActivity(userId, order(orderId, "5000.00", dayInCurrentMonth(4)));
    assertThat(currentMonthSpend(userId)).isEqualByComparingTo("5000.00");

    // A refund is the same order re-asserted at a lower amount. The bucket follows down; a
    // write-once month total could never have expressed this.
    ingestion.recordActivity(userId, order(orderId, "1500.00", dayInCurrentMonth(4)));

    assertThat(currentMonthSpend(userId)).isEqualByComparingTo("1500.00");
  }

  @Test
  void orderCountsTowardTheMonthItHappenedIn_notWhenItArrives() {
    long userId = 8104L;
    // An order placed on the last day of last month, delivered late (now, this month). It must land
    // in last month's bucket and leave this month at zero — the split-brain the server-clock key
    // would have got wrong.
    Instant lastDayOfLastMonth =
        YearMonth.now(ZoneOffset.UTC)
            .minusMonths(1)
            .atEndOfMonth()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    ingestion.recordActivity(userId, order(910401L, "9000.00", lastDayOfLastMonth));

    assertThat(currentMonthSpend(userId))
        .as("a late-delivered prior-month order does not inflate this month")
        .isEqualByComparingTo(BigDecimal.ZERO);

    String lastMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();
    Instant from = YearMonth.parse(lastMonth).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    assertThat(orderEventRepository.sumForUserBetween(userId, from, to))
        .as("but it is present in the month it actually happened in")
        .isEqualByComparingTo("9000.00");
  }

  @Test
  void spendIsQueryableOverAnArbitraryRange() {
    long userId = 8105L;
    ingestion.recordActivity(userId, order(910501L, "1000.00", dayInCurrentMonth(1)));
    ingestion.recordActivity(userId, order(910502L, "2000.00", dayInCurrentMonth(10)));

    Instant monthStart =
        YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant afterDay10 = dayInCurrentMonth(11);

    // A rolling window is just a different range. The monthly bucket structurally could not answer
    // this; the events table answers it directly.
    UserActivity ignored = userActivityService.currentActivity(userId);
    assertThat(ignored).isNotNull();
    assertThat(orderEventRepository.sumForUserBetween(userId, monthStart, afterDay10))
        .isEqualByComparingTo("3000.00");
  }
}
