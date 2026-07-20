package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrent cursor advances must never lower it. Wraps advance in {@link OptimisticRetry}, the way
 * {@code TierReconciliationJob} does, since the retry lives at the call site.
 */
class JobCursorConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String JOB = "concurrency-test-cursor";

  @Autowired JobCursorService cursors;
  @Autowired OptimisticRetry optimisticRetry;

  @Test
  void concurrentAdvances_neverRegressTheCursor() throws InterruptedException {
    cursors.loadOrCreate(JOB);

    int writers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(writers);

    // Distinct targets; the highest must win and no writer may lower the cursor.
    for (int i = 0; i < writers; i++) {
      long target = (i + 1) * 100L;
      pool.submit(
          () -> {
            try {
              start.await();
              optimisticRetry.execute(
                  () -> {
                    cursors.advance(JOB, target);
                    return null;
                  },
                  writers + 4);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    start.countDown(); // release all at once to maximise contention
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    assertThat(cursors.loadOrCreate(JOB).getLastId())
        .as("the highest advance wins; a concurrent write never lowers the cursor")
        .isEqualTo(writers * 100L);
  }
}
