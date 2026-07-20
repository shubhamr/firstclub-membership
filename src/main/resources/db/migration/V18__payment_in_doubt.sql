-- ---------------------------------------------------------------------------
-- Adds IN_DOUBT to the payment status domain.
--
-- IN_DOUBT: the provider neither confirmed nor declined — the charge must be reconciled, not
-- assumed failed. A read timeout, a connection reset and an open circuit breaker all report that
-- the CALL failed, not that the CAPTURE failed. Recording them as ABANDONED would assert "no
-- money moved", and that assertion is the dangerous one to get wrong: reconciliation scans only
-- CHARGED, so a captured-but-timed-out charge would be written off as a non-event and the member
-- charged a second time on retry.
--
-- A state meaning "unknown" is actionable in a way that a guess is not. Resolution is to re-call
-- the gateway with the same reference: the idempotency key replays the original outcome if a
-- capture happened, and charges if it did not.
--
-- Safe on a live table — this only widens a CHECK constraint. Postgres re-validates existing rows
-- under a brief ACCESS EXCLUSIVE lock on `payment`; at ledger sizes where that matters, add the
-- constraint NOT VALID and run VALIDATE CONSTRAINT separately.
-- ---------------------------------------------------------------------------

alter table payment drop constraint ck_payment_status;

alter table payment add constraint ck_payment_status
    check (status in ('INTENDED','CHARGED','APPLIED','ABANDONED','REFUNDED','IN_DOUBT'));

-- Partial index mirroring idx_payment_unapplied. Stuck CHARGED and IN_DOUBT are the two
-- "something is wrong with money" questions, asked every few minutes indefinitely, so both must
-- stay index-only scans over a handful of rows however large the ledger grows.
create index idx_payment_in_doubt on payment(created_at) where status = 'IN_DOUBT';

comment on column payment.status is
    'INTENDED -> CHARGED -> APPLIED is the happy path. ABANDONED = explicitly declined '
    '(no money moved). IN_DOUBT = ambiguous failure, capture status unknown, resolve by '
    're-calling the gateway with the same reference.';
