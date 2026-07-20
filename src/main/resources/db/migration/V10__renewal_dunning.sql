-- ---------------------------------------------------------------------------
-- Dunning state on the subscription: consecutive failed renewal charges, and the next retry time.
--
-- Held on the row rather than in a scheduler so retry state survives restart and so the renewal
-- sweep can select what is due with a single indexed predicate.
-- ---------------------------------------------------------------------------
alter table subscription add column renewal_attempts int not null default 0;
alter table subscription add column next_retry_at timestamptz;
