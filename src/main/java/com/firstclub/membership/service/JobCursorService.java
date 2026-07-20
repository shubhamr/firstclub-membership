package com.firstclub.membership.service;

import com.firstclub.membership.model.JobCursor;

/** Durable resume points for sweep jobs whose queries are not self-draining. */
public interface JobCursorService {

  /** The job's cursor, created at zero on first use. */
  JobCursor loadOrCreate(String jobName);

  /** Persist a new high-water mark. */
  void advance(String jobName, long lastId);

  /** A full pass finished — start the next one from the beginning. */
  void rewind(String jobName);
}
