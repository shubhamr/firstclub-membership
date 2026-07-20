-- ---------------------------------------------------------------------------
-- Credit notes: the accounting counterpart to upgrade proration.
--
-- Subscriptions are paid in advance, so an early cancel or refund leaves an unused portion of the
-- period. Recording that as a credit row keeps the obligation auditable instead of implicit in an
-- adjusted charge.
-- ---------------------------------------------------------------------------
create table credit_note (
    id              bigserial     primary key,
    subscription_id bigint        not null references subscription(id),
    user_id         bigint        not null,
    amount          numeric(10, 2) not null,
    reason          varchar(64)   not null,
    created_at      timestamptz   not null default now()
);
create index idx_credit_note_user on credit_note(user_id, created_at);
