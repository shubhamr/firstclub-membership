-- ---------------------------------------------------------------------------
-- Per-month spend buckets, superseding the scalar user_order_stats.monthly_spend.
--
-- Spend has to be windowed to a month for a spend-gated tier to be losable. A keyless scalar
-- overwritten in place cannot express that: a user who once spent 20k reads as a 20k month
-- forever and never stops qualifying. A month with no bucket now reads as zero.
--
-- order_count and cohorts stay on user_order_stats and stay lifetime-scoped: only order *value*
-- is qualified with "in a month".
--
-- Destructive. The backfill and the DROP COLUMN share one transaction (Postgres has transactional
-- DDL), so it is all-or-nothing. Safe for the single-instance deploy this service targets; a
-- rolling deploy would ship the additive half first and drop the column once no instance reads it.
-- ---------------------------------------------------------------------------

create table user_monthly_spend (
    user_id    bigint        not null,
    -- 'YYYY-MM'. varchar, not char: Postgres reports char(n) as bpchar, which
    -- Hibernate's ddl-auto=validate rejects against a String field (as in V15).
    year_month varchar(7)    not null,
    amount     numeric(12,2) not null default 0,
    updated_at timestamptz   not null default now(),
    primary key (user_id, year_month)
);

-- Carry the existing scalar into the current bucket so nobody loses eligibility on deploy.
-- 'now() at time zone utc' matches ClockConfig's Clock.systemUTC(), so database and application
-- agree on which month this is even when the database session runs in another timezone.
insert into user_monthly_spend (user_id, year_month, amount, updated_at)
select user_id, to_char((now() at time zone 'utc'), 'YYYY-MM'), monthly_spend, updated_at
from user_order_stats
where monthly_spend > 0;

alter table user_order_stats drop column monthly_spend;

-- No secondary index: every access is (user_id, year_month) equality, which the primary key's
-- btree already serves.
