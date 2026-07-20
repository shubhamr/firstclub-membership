-- ---------------------------------------------------------------------------
-- Per-tier price premium over the plan's base price.
--
-- A multiplier rather than an absolute price per (plan, tier) pair: it keeps pricing to one row
-- per tier, and it gives a mid-cycle upgrade a real prorated delta to charge.
-- ---------------------------------------------------------------------------
alter table membership_tier add column price_multiplier numeric(4, 2) not null default 1.00;
update membership_tier set price_multiplier = 1.00 where code = 'SILVER';
update membership_tier set price_multiplier = 1.50 where code = 'GOLD';
update membership_tier set price_multiplier = 2.00 where code = 'PLATINUM';
