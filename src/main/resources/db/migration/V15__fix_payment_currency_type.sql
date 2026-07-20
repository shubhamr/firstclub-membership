-- ---------------------------------------------------------------------------
-- payment.currency becomes varchar(3).
--
-- Postgres reports char(n) as bpchar, which Hibernate's ddl-auto=validate rejects against a String
-- field. varchar(3) keeps the three-character intent — matched by @Column(length = 3) on the
-- entity — without char's blank-padding semantics.
--
-- PRODUCTION NOTE — THIS REWRITES THE TABLE. bpchar -> varchar is not binary-coercible, so
-- Postgres rewrites every row and holds ACCESS EXCLUSIVE on `payment` throughout: all payment
-- reads and writes block. Negligible on a young ledger, minutes of payment downtime on a large
-- one. At scale, take a maintenance window, or go online: add currency_v2, backfill in batches
-- behind a sync trigger, swap in a short transaction, drop the old column.
-- ---------------------------------------------------------------------------

alter table payment alter column currency type varchar(3);
