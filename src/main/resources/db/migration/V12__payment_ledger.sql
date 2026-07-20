-- ---------------------------------------------------------------------------
-- Payment ledger — the local system of record for money.
--
-- Gives a charge one authoritative, queryable row. A JSON blob on a subscription event cannot be
-- reconciled, uniquely constrained, or alerted on, and the gateway's own records are not available
-- to this service's invariants.
--
-- Reserve -> charge -> apply can take money and then fail to apply: a concurrent cancel, a lost
-- optimistic-lock race, or a crash between the charge and the apply transaction. The status column
-- makes that gap observable rather than merely rare:
--   INTENDED  — written BEFORE the gateway call; the intent to charge is durable first
--   CHARGED   — the gateway approved; money has moved
--   APPLIED   — the corresponding subscription state change committed
--   ABANDONED — declined or threw; no money moved
--   REFUNDED  — reversed
--
-- A row stuck in CHARGED means money was taken and delivery did not happen. The partial index
-- below is what makes finding those cheap enough to alert on.
-- ---------------------------------------------------------------------------
create table payment (
    id              bigserial      primary key,
    subscription_id bigint         not null references subscription(id),
    user_id         bigint         not null,
    -- Doubles as the gateway idempotency key. Unique, so a replayed charge cannot
    -- create a second ledger row.
    reference       varchar(128)   not null,
    amount          numeric(10, 2) not null,
    currency        char(3)        not null default 'INR',
    status          varchar(16)    not null,
    gateway_txn_id  varchar(128),
    purpose         varchar(24)    not null,  -- SUBSCRIBE | UPGRADE | RENEWAL | TRIAL_CONVERSION
    failure_reason  varchar(255),
    version         bigint         not null default 0,
    created_at      timestamptz    not null default now(),
    updated_at      timestamptz    not null default now(),
    constraint uq_payment_reference unique (reference),
    constraint ck_payment_status check (
        status in ('INTENDED', 'CHARGED', 'APPLIED', 'ABANDONED', 'REFUNDED')
    )
);

create index idx_payment_user on payment(user_id, created_at desc);
create index idx_payment_subscription on payment(subscription_id);

-- Reconciliation index, deliberately partial: the table is dominated by APPLIED rows and only the
-- stuck CHARGED ones are worth sweeping, so the index stays small at any payment volume.
--
-- The reconciliation query it serves — money taken but not applied, past the grace window:
--   select * from payment where status = 'CHARGED' and created_at < now() - interval '15 minutes';
create index idx_payment_unapplied on payment(created_at) where status = 'CHARGED';
