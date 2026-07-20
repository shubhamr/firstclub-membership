-- ---------------------------------------------------------------------------
-- Core schema: plans, tiers, progression criteria, benefits, subscriptions,
-- activity stats and the idempotency store.
--
-- Flyway owns the schema (Hibernate runs ddl-auto=validate). Scaling-sensitive
-- columns are indexed here rather than discovered under load.
-- ---------------------------------------------------------------------------

create table membership_plan (
    id            bigserial primary key,
    cadence       varchar(16)   not null,
    name          varchar(64)   not null,
    price         numeric(10,2) not null,
    duration_days integer       not null,
    active        boolean       not null default true,
    created_at    timestamptz   not null default now(),
    updated_at    timestamptz   not null default now(),
    constraint uq_plan_cadence unique (cadence)
);

-- Tiers are rows, not a code enum: rank ordering and membership are configurable at runtime.
create table membership_tier (
    id         bigserial primary key,
    code       varchar(32) not null,
    name       varchar(64) not null,
    rank       integer     not null,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_tier_code unique (code),
    constraint uq_tier_rank unique (rank)
);

-- Per-tier progression criteria driving the rule engine. Every threshold is nullable: a null
-- threshold means that rule does not apply to this tier, which is how a tier stays unconditional.
create table tier_criteria (
    id                bigserial primary key,
    tier_id           bigint        not null references membership_tier(id),
    min_orders        integer,
    min_monthly_spend numeric(12,2),
    cohorts           jsonb         not null default '[]'::jsonb,
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    constraint uq_criteria_tier unique (tier_id)
);

create table benefit (
    id          bigserial primary key,
    code        varchar(48)  not null,
    type        varchar(32)  not null,
    name        varchar(96)  not null,
    description varchar(255),
    active      boolean      not null default true,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uq_benefit_code unique (code)
);

-- Tier -> benefit mapping. params carries the per-tier tuning (e.g. {"discountPct":10}), so a
-- perk can be re-tuned per tier without new benefit rows or code.
create table tier_benefit (
    id         bigserial primary key,
    tier_id    bigint      not null references membership_tier(id),
    benefit_id bigint      not null references benefit(id),
    params     jsonb       not null default '{}'::jsonb,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_tier_benefit unique (tier_id, benefit_id)
);

-- `version` backs JPA optimistic locking on concurrent subscription mutation.
create table subscription (
    id         bigserial primary key,
    user_id    bigint      not null,
    plan_id    bigint      not null references membership_plan(id),
    tier_id    bigint      not null references membership_tier(id),
    status     varchar(16) not null,
    start_at   timestamptz not null,
    expires_at timestamptz not null,
    auto_renew boolean     not null default true,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index idx_subscription_user on subscription(user_id);
-- At most one active subscription per user, enforced in the database so concurrent subscribes
-- cannot both win. The WHERE clause is what allows a user to hold many historical rows.
create unique index uq_active_subscription_per_user on subscription(user_id) where status = 'ACTIVE';
-- Partial, matching the expiry sweep's predicate, so the sweep stays off a full-table scan.
create index idx_subscription_expires_at on subscription(expires_at) where status = 'ACTIVE';

-- Append-only lifecycle audit trail.
create table subscription_event (
    id              bigserial primary key,
    subscription_id bigint      not null references subscription(id),
    user_id         bigint      not null,
    type            varchar(24) not null,
    detail          jsonb       not null default '{}'::jsonb,
    created_at      timestamptz not null default now()
);
create index idx_sub_event_user_created on subscription_event(user_id, created_at);

-- Activity snapshot the tier rules read. Denormalised on purpose: tier evaluation must not depend
-- on an order-history scan.
create table user_order_stats (
    user_id       bigint        primary key,
    order_count   integer       not null default 0,
    monthly_spend numeric(12,2) not null default 0,
    cohorts       jsonb         not null default '[]'::jsonb,
    updated_at    timestamptz   not null default now()
);

-- Idempotency store for mutation endpoints. Unique (key, endpoint) collapses a client retry onto
-- the stored response instead of performing the mutation twice.
create table idempotency_key (
    id            bigserial primary key,
    idem_key      varchar(128) not null,
    endpoint      varchar(128) not null,
    status_code   integer      not null,
    response_body text         not null,
    created_at    timestamptz  not null default now(),
    constraint uq_idem_key_endpoint unique (idem_key, endpoint)
);
