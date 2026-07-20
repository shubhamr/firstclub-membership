-- ---------------------------------------------------------------------------
-- Durable cursors for sweep jobs that are NOT self-draining.
--
-- Most scheduled jobs here drain themselves: a renewed subscription stops matching "due for
-- renewal", so page 0 is always the right page. Tier reconciliation is different — re-evaluating
-- a member does not remove them from "active subscribers", so the set never shrinks and the job
-- has to remember where it stopped.
--
-- Keyset pagination alone only orders within a run. Without persistence the cursor resets each
-- run, capping coverage at MAX_BATCHES_PER_RUN * BATCH members and reprocessing the same prefix
-- forever while the progress gauge still reports a healthy count. A durable cursor is what makes
-- coverage unbounded: each run resumes where the last ended and wraps to 0 at the end of the set.
-- ---------------------------------------------------------------------------

create table job_cursor (
    job_name   varchar(64)  primary key,
    last_id    bigint       not null default 0,
    updated_at timestamptz  not null default now()
);

comment on table job_cursor is
    'Resume points for non-self-draining sweep jobs. last_id = highest id processed; '
    '0 means start from the beginning of the set.';

insert into job_cursor (job_name, last_id) values ('tier-reconciliation', 0);
