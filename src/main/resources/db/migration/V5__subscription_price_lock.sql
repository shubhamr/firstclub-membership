-- ---------------------------------------------------------------------------
-- Price lock on the subscription.
--
-- The price paid is captured on the subscription rather than read back from the plan, so a later
-- plan price change cannot retroactively reprice an existing subscriber. This column is also what
-- renewal charges against.
-- ---------------------------------------------------------------------------
alter table subscription add column price_paid numeric(10, 2);
update subscription set price_paid = 0 where price_paid is null;
alter table subscription alter column price_paid set not null;
