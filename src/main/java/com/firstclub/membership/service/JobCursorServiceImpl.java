package com.firstclub.membership.service;

import com.firstclub.membership.model.JobCursor;
import com.firstclub.membership.repository.JobCursorRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link JobCursorService}.
 *
 * <p>Every method is {@code REQUIRES_NEW}. A sweep job processes each item in its own transaction
 * so one bad row cannot abort the batch; the cursor update has to be independent for the same
 * reason. Joining an ambient transaction would tie the resume point to work that might roll back,
 * and a cursor that rolls back re-processes rows instead of making forward progress.
 */
@Service
@RequiredArgsConstructor
public class JobCursorServiceImpl implements JobCursorService {

  private final JobCursorRepository repository;
  private final Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public JobCursor loadOrCreate(String jobName) {
    return load(jobName);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void advance(String jobName, long lastId) {
    JobCursor cursor = load(jobName);
    // Monotonic: only move forward. If another runner already passed this id, no-op; otherwise the
    // write bumps @Version so a concurrent advance conflicts instead of being silently lost.
    if (cursor.getLastId() >= lastId) {
      return;
    }
    cursor.advanceTo(lastId, clock.instant());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void rewind(String jobName) {
    load(jobName).rewind(clock.instant());
  }

  /**
   * Loads the cursor row, creating it on first use.
   *
   * <p>Private so the public entry points do not self-invoke: a {@code this.loadOrCreate(...)} call
   * would bypass the proxy and silently lose the {@code REQUIRES_NEW} boundary it appears to get.
   */
  private JobCursor load(String jobName) {
    return repository
        .findById(jobName)
        .orElseGet(() -> repository.save(new JobCursor(jobName, clock.instant())));
  }
}
