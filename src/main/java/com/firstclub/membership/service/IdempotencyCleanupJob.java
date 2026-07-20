package com.firstclub.membership.service;

import com.firstclub.membership.repository.IdempotencyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reaps stored idempotency keys past their retention window. Without this the {@code
 * idempotency_key} table grows forever — a real, if unglamorous, production failure mode.
 *
 * <p>In a multi-instance deployment this should run under a shard lock (ShedLock) so only one node
 * purges.
 */
@Component
@Slf4j
public class IdempotencyCleanupJob {

  private final IdempotencyRepository repository;
  private final Clock clock;
  private final Duration retention;

  public IdempotencyCleanupJob(
      IdempotencyRepository repository,
      Clock clock,
      @Value("${membership.idempotency-retention-hours:24}") long retentionHours) {
    this.repository = repository;
    this.clock = clock;
    this.retention = Duration.ofHours(retentionHours);
  }

  private static final int BATCH = 5_000;

  /** Bounds one run so a large backlog cannot occupy the scheduler thread indefinitely. */
  private static final int MAX_BATCHES_PER_RUN = 200;

  /**
   * Purge in bounded batches, each in its own transaction.
   *
   * <p>A single-statement purge would hold one write transaction open for the whole backlog,
   * pinning the vacuum horizon. Small batches keep each transaction short and leave the table
   * vacuumable while the purge runs.
   */
  @Scheduled(cron = "${membership.idempotency-cleanup-cron}")
  public void purge() {
    Instant cutoff = clock.instant().minus(retention);
    long removed = 0;
    int batches = 0;
    int deleted;
    do {
      deleted = repository.purgeBatch(cutoff, BATCH);
      removed += deleted;
    } while (deleted == BATCH && ++batches < MAX_BATCHES_PER_RUN);

    if (removed > 0) {
      log.info("Purged {} expired idempotency keys", removed);
    }
    if (batches >= MAX_BATCHES_PER_RUN) {
      log.warn(
          "Idempotency purge hit the {}-batch cap after {} keys; more remain for the next run",
          MAX_BATCHES_PER_RUN,
          removed);
    }
  }
}
