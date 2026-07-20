package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.model.JobCursor;
import com.firstclub.membership.repository.JobCursorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins forward progress for sweep jobs whose queries are not self-draining.
 *
 * <p>Most jobs here drain themselves — a renewed subscription stops matching "due for renewal", so
 * page 0 is always right. Tier reconciliation does not: re-evaluating a member leaves them an
 * active member. Keyset pagination alone only covers the intra-run half; if the cursor also resets
 * to zero each run, coverage is capped at {@code MAX_BATCHES_PER_RUN * BATCH} and the tail of the
 * member base is never reached, while the throughput gauge keeps reporting a healthy number.
 */
class JobProgressIntegrationTest extends AbstractIntegrationTest {

  private static final String CURSOR = "job-progress-test";

  @Autowired JobCursorService cursors;
  @Autowired JobCursorRepository repository;

  @Test
  void cursorIsCreatedAtZeroOnFirstUse() {
    JobCursor cursor = cursors.loadOrCreate(CURSOR + "-fresh");
    assertThat(cursor.getLastId()).isZero();
  }

  @Test
  void cursorSurvivesAcrossRuns_soCoverageIsNotCappedPerRun() {
    cursors.loadOrCreate(CURSOR);
    cursors.advance(CURSOR, 12_345L);

    // A second run must resume where the first stopped, not from 0; otherwise members past the
    // per-run batch cap are never reconciled at all.
    assertThat(repository.findById(CURSOR).orElseThrow().getLastId()).isEqualTo(12_345L);
    assertThat(cursors.loadOrCreate(CURSOR).getLastId()).isEqualTo(12_345L);
  }

  @Test
  void rewindStartsAFreshPassOnlyWhenTheSetIsDrained() {
    cursors.loadOrCreate(CURSOR + "-wrap");
    cursors.advance(CURSOR + "-wrap", 999L);
    cursors.rewind(CURSOR + "-wrap");

    assertThat(repository.findById(CURSOR + "-wrap").orElseThrow().getLastId())
        .as("reaching the end wraps to 0 so the next run re-scans from the beginning")
        .isZero();
  }
}
