-- ---------------------------------------------------------------------------
-- Per-order spend events: the granular source of truth behind user_monthly_spend.
--
-- user_monthly_spend stays exactly as it is read (a point lookup on (user_id, year_month)), but it
-- stops being asserted by the caller and becomes derived: recomputed as SUM(amount) over the events
-- for that user and month. Storing the finer grain is what makes a monthly total reproducible after
-- the fact, correctable when an order is refunded, and windowable over a rolling range. None of that
-- is expressible once orders have been collapsed into a month total, because aggregation is one-way.
--
-- Not a materialized view: Postgres REFRESH is a full rebuild, which would break the read-your-writes
-- at ingest (recordActivity writes spend then immediately re-evaluates the tier off it). The bucket
-- is maintained synchronously instead, in the same write.
--
-- order_id is the primary key, so replaying the same order under at-least-once delivery is a no-op
-- rather than a double count. This is the idempotency an append-only events table would otherwise
-- need bolted on; here it falls out of the key.
--
-- Additive and non-destructive: user_monthly_spend is retained as the derived cache, so a rolling
-- deploy can ship this table first and cut the writer over afterwards.
-- ---------------------------------------------------------------------------

create table user_order_event (
    order_id    bigint        not null primary key,
    user_id     bigint        not null,
    amount      numeric(12,2) not null,
    -- When the order happened, per the caller. The month a spend event counts toward is derived
    -- from this, never from the server's clock at receipt, so a caller in another timezone near a
    -- month boundary cannot file a total under the wrong month.
    occurred_at timestamptz   not null,
    received_at timestamptz   not null default now()
);

-- Serves both the per-month recompute (equality on user_id, range on the derived month) and a
-- future rolling-window SUM. INCLUDE (amount) makes the covering read index-only.
-- For a live production database this should be `create index concurrently` under
-- `-- flyway:executeInTransaction=false` (see V13); plain create index suits the single-instance
-- target here.
create index idx_order_event_user_time on user_order_event (user_id, occurred_at) include (amount);

-- Carry the existing monthly buckets into events so nobody loses eligibility on deploy, mirroring
-- the V16 backfill. One synthetic event per bucket, dated to the first of that month (UTC to match
-- ClockConfig). Synthetic ids are negative so they can never collide with a real order id.
insert into user_order_event (order_id, user_id, amount, occurred_at, received_at)
select
    - row_number() over (order by user_id, year_month),
    user_id,
    amount,
    ((year_month || '-01')::timestamp at time zone 'utc'),
    now()
from user_monthly_spend
where amount > 0;
