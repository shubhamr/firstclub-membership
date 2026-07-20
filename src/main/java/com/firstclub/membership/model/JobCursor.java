package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Where a non-self-draining sweep job stopped last run.
 *
 * <p>Self-draining jobs (renewal, expiry, trial conversion) mutate a column in their own predicate,
 * so processed rows stop matching and page 0 is always correct. Reconciliation does not: an active
 * subscriber stays an active subscriber after being re-evaluated. Without a durable cursor such a
 * job re-processes its first N rows forever, and does so invisibly — its throughput counter stays
 * high the whole time.
 */
@Entity
@Table(name = "job_cursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobCursor {

  @Id
  @Column(name = "job_name", nullable = false, updatable = false)
  private String jobName;

  @Column(name = "last_id", nullable = false)
  private long lastId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  // Optimistic-lock counter; a concurrent cursor write conflicts instead of being lost.
  @Version
  @Column(nullable = false)
  @Getter(AccessLevel.NONE)
  private long version;

  public JobCursor(String jobName, Instant now) {
    this.jobName = jobName;
    this.lastId = 0;
    this.updatedAt = now;
  }

  public void advanceTo(long lastId, Instant now) {
    this.lastId = lastId;
    this.updatedAt = now;
  }

  /** A full pass finished; start the next one from the beginning of the set. */
  public void rewind(Instant now) {
    this.lastId = 0;
    this.updatedAt = now;
  }
}
