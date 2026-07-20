package com.firstclub.membership.service;

import com.firstclub.membership.model.JobCursor;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.SubscriptionRepository.ActiveSubscriber;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically recomputes tiers for active subscribers, backfilling users left on a stale tier
 * because an order/activity event was delayed or lost.
 *
 * <p><b>Pages on {@code id > lastId}, and persists that cursor.</b> Other jobs can re-read page
 * zero every run because their queries drain themselves — a renewed subscription stops matching
 * "due for renewal". This one doesn't: re-evaluating a user leaves them in "active subscribers".
 * Fixed paging would loop over the same lowest-id members forever while still reporting success, so
 * the resume point lives in {@link JobCursorService} and wraps to zero only when the set is truly
 * drained. The {@code users_covered} gauge is what would make a regression here visible.
 *
 * <p>Each user is re-evaluated in its own transaction (a proxied service call) so one failure can't
 * abort the batch, and via {@link OptimisticRetry} so a clash with a concurrent manual tier change
 * retries instead of dropping. Multi-instance deployments want a ShedLock shard lock — duplicate
 * runs waste work but can't corrupt, since {@code reevaluateTier} is idempotent.
 */
@Component
@Slf4j
public class TierReconciliationJob {

  private static final int BATCH = 500;
  private static final int MAX_REEVAL_ATTEMPTS = 3;
  private static final String CURSOR_NAME = "tier-reconciliation";

  /** Safety valve: bounds a single run so a pathological dataset cannot spin forever. */
  private static final int MAX_BATCHES_PER_RUN = 200;

  private final SubscriptionRepository subscriptionRepository;
  private final SubscriptionService subscriptionService;
  private final OptimisticRetry optimisticRetry;
  private final JobCursorService cursors;
  private final AtomicInteger usersCovered = new AtomicInteger();

  public TierReconciliationJob(
      SubscriptionRepository subscriptionRepository,
      SubscriptionService subscriptionService,
      OptimisticRetry optimisticRetry,
      JobCursorService cursors,
      MeterRegistry metrics) {
    this.subscriptionRepository = subscriptionRepository;
    this.subscriptionService = subscriptionService;
    this.optimisticRetry = optimisticRetry;
    this.cursors = cursors;
    metrics.gauge("membership.reconciliation.users_covered", usersCovered);
  }

  @Scheduled(cron = "${membership.reconciliation-cron}")
  public void reconcile() {
    // Resume where the previous run stopped. Starting from 0 every run would cap coverage at
    // MAX_BATCHES_PER_RUN * BATCH members while still reporting a full batch every time.
    JobCursor cursor = cursors.loadOrCreate(CURSOR_NAME);
    long lastId = cursor.getLastId();
    int processed = 0;
    int batches = 0;
    boolean drained = false;

    while (batches++ < MAX_BATCHES_PER_RUN) {
      List<ActiveSubscriber> batch =
          subscriptionRepository.findActiveAfterId(lastId, PageRequest.of(0, BATCH));
      if (batch.isEmpty()) {
        drained = true;
        break;
      }
      for (ActiveSubscriber subscriber : batch) {
        try {
          optimisticRetry.execute(
              () -> subscriptionService.reevaluateTier(subscriber.getUserId()),
              MAX_REEVAL_ATTEMPTS);
          processed++;
        } catch (RuntimeException e) {
          log.warn("Reconcile skipped user {}: {}", subscriber.getUserId(), e.toString());
        }
      }
      lastId = batch.getLast().getId();
    }

    // End of set wraps to 0 for a fresh pass; otherwise persist the high-water mark. The write is
    // retried because the cursor is @Version-guarded: a concurrent instance's advance conflicts
    // rather than being lost, and the monotonic advance makes the retry a no-op if already past.
    if (drained) {
      optimisticRetry.execute(
          () -> {
            cursors.rewind(CURSOR_NAME);
            return null;
          },
          MAX_REEVAL_ATTEMPTS);
      log.info("Tier reconciliation completed a full pass ({} users this run)", processed);
    } else {
      long highWaterMark = lastId;
      optimisticRetry.execute(
          () -> {
            cursors.advance(CURSOR_NAME, highWaterMark);
            return null;
          },
          MAX_REEVAL_ATTEMPTS);
      log.info(
          "Tier reconciliation processed {} users up to id {}; resuming there next run",
          processed,
          lastId);
    }
    usersCovered.set(processed);
  }
}
