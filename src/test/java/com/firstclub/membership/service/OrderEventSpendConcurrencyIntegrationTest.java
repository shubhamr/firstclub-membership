package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrent orders for one user must all land in the month bucket. Without the per-user advisory
 * lock in {@link MonthlySpendRecalculator} the derived-sum recompute loses spend under this race.
 */
class OrderEventSpendConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired ActivityIngestionService ingestion;
  @Autowired UserActivityService userActivityService;

  @BeforeEach
  void seedUsers() {
    seedUser(8801L);
  }

  @Test
  void concurrentOrdersForOneUser_sumIntoTheBucketWithoutLoss() throws InterruptedException {
    long userId = 8801L;
    int orders = 16;
    BigDecimal each = new BigDecimal("1000.00");
    Instant when = YearMonth.now(ZoneOffset.UTC).atDay(10).atStartOfDay(ZoneOffset.UTC).toInstant();

    ExecutorService pool = Executors.newFixedThreadPool(orders);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(orders);

    for (int i = 0; i < orders; i++) {
      long orderId = 880100L + i;
      pool.submit(
          () -> {
            try {
              start.await();
              ingestion.recordActivity(
                  userId, new ActivityUpdateRequest(1, orderId, each, when, List.of()));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    start.countDown(); // release all threads together to maximise contention
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    assertThat(userActivityService.currentActivity(userId).monthlySpend())
        .as("every concurrent order's spend must be in the bucket, none lost")
        .isEqualByComparingTo(each.multiply(BigDecimal.valueOf(orders)));
  }
}
