-- ---------------------------------------------------------------------------
-- Widen the one-live-subscription-per-user index to cover reservations.
--
-- Subscribe is reserve -> charge -> activate, with the gateway called outside the DB transaction:
-- the row is inserted PENDING, then flipped to ACTIVE once the charge succeeds. The invariant has
-- to hold before any money moves, so the partial unique index counts a PENDING reservation as
-- live. Restricted to ACTIVE alone, two concurrent subscribes would both reserve and both charge.
-- ---------------------------------------------------------------------------
drop index if exists uq_active_subscription_per_user;
create unique index uq_active_subscription_per_user
    on subscription (user_id)
    where status in ('PENDING', 'ACTIVE');
