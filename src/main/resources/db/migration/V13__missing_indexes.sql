-- ---------------------------------------------------------------------------
-- Indexes for the scheduled-job query patterns.
--
-- Each is PARTIAL and matches its query's predicate exactly, so it stays small even as
-- `subscription` grows into the millions — the same technique as idx_subscription_expires_at.
--
-- These partial indexes are only usable when the planner can prove the query predicate implies
-- the index predicate, which requires `status` to reach SQL as a LITERAL. Hibernate renders JPQL
-- enum constants inline, so it does. Rewriting any of these queries to pass SubscriptionStatus as
-- a PARAMETER turns the scan sequential silently, with no test failure to catch it.
--
-- PRODUCTION NOTE — plain CREATE INDEX takes a SHARE lock on `subscription`, blocking every
-- INSERT/UPDATE/DELETE for the duration of the build: subscribe, renew, cancel and upgrade stall
-- together, for minutes on a large table. Against a live table use CREATE INDEX CONCURRENTLY,
-- which must run outside a transaction:
--     -- flyway:executeInTransaction=false
--     create index concurrently idx_subscription_trial_end on ...;
-- Non-concurrent here because these tables are small wherever this runs, and CONCURRENTLY can
-- leave an INVALID index behind on failure — a worse default for a dev schema.
-- ---------------------------------------------------------------------------

-- TrialConversionJob -> findConvertibleTrialIds:
--   status = ACTIVE and trialEnd is not null and trialEnd <= :now
-- Runs every 10 minutes, so an unindexed trial_end is a repeated full scan.
create index idx_subscription_trial_end
    on subscription(trial_end)
    where status = 'ACTIVE' and trial_end is not null;

-- ExpirySweeper -> findStalePendingIds:
--   status = 'PENDING' and startAt < :cutoff
-- Needs its own index: uq_active_subscription_per_user cannot serve this, being keyed on user_id
-- and spanning PENDING+ACTIVE.
create index idx_subscription_pending
    on subscription(start_at)
    where status = 'PENDING';

-- TierReconciliationJob -> findActiveAfterId (keyset pagination):
--   status = ACTIVE and id > :lastId order by id
create index idx_subscription_active_id
    on subscription(id)
    where status = 'ACTIVE';

-- Covers the foreign key: without it, both audit-trail lookups by subscription and Postgres'
-- own referential-integrity checks scan subscription_event.
create index idx_sub_event_subscription
    on subscription_event(subscription_id);
