-- ---------------------------------------------------------------------------
-- Seed data: plans, tiers, progression criteria, benefits, tier->benefit mappings.
--
-- Every row here is editable at runtime through the admin API. These values are a starting
-- configuration, not a contract — nothing in the code assumes these particular rows exist.
-- ---------------------------------------------------------------------------

insert into membership_plan (cadence, name, price, duration_days) values
    ('MONTHLY',   'Monthly',    199.00,  30),
    ('QUARTERLY', 'Quarterly',  499.00,  90),
    ('YEARLY',    'Yearly',    1499.00, 365);

-- Rank ascending: Silver < Gold < Platinum.
insert into membership_tier (code, name, rank) values
    ('SILVER',   'Silver',   1),
    ('GOLD',     'Gold',     2),
    ('PLATINUM', 'Platinum', 3);

-- SILVER carries no thresholds, which is what makes it the unconditional base tier — the rule
-- engine derives that from the absence of criteria rather than hardcoding the code 'SILVER'.
-- A tier qualifies when ANY configured criterion is met (OR semantics; see TierAssignmentService).
insert into tier_criteria (tier_id, min_orders, min_monthly_spend, cohorts)
select id, null, null, '[]'::jsonb           from membership_tier where code = 'SILVER';
insert into tier_criteria (tier_id, min_orders, min_monthly_spend, cohorts)
select id, 5,   5000.00, '["PREMIER"]'::jsonb from membership_tier where code = 'GOLD';
insert into tier_criteria (tier_id, min_orders, min_monthly_spend, cohorts)
select id, 15, 20000.00, '["VIP"]'::jsonb     from membership_tier where code = 'PLATINUM';

insert into benefit (code, type, name, description) values
    ('FREE_DELIVERY',   'FREE_DELIVERY',   'Free Delivery',    'Free delivery on eligible orders'),
    ('EXTRA_DISCOUNT',  'EXTRA_DISCOUNT',  'Extra Discount',   'Additional %-discount on selected items/categories'),
    ('EXCLUSIVE_DEALS', 'EXCLUSIVE_DEALS', 'Exclusive Deals',  'Access to exclusive deals and early access to sales'),
    ('EXCLUSIVE_COUPONS','EXCLUSIVE_COUPONS','Exclusive Coupons','Tier-only coupons released to members each cycle'),
    ('PRIORITY_SUPPORT','PRIORITY_SUPPORT','Priority Support', 'Priority customer support for premium members');

-- Tier -> benefit mappings. Higher tiers widen the same perks via params rather than gaining
-- separate benefit rows.

-- SILVER
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"minOrderValue":500}'::jsonb
from membership_tier t, benefit b where t.code='SILVER' and b.code='FREE_DELIVERY';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"discountPct":5}'::jsonb
from membership_tier t, benefit b where t.code='SILVER' and b.code='EXTRA_DISCOUNT';

-- GOLD
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"minOrderValue":0}'::jsonb
from membership_tier t, benefit b where t.code='GOLD' and b.code='FREE_DELIVERY';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"discountPct":10}'::jsonb
from membership_tier t, benefit b where t.code='GOLD' and b.code='EXTRA_DISCOUNT';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{}'::jsonb
from membership_tier t, benefit b where t.code='GOLD' and b.code='EXCLUSIVE_DEALS';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"couponsPerCycle":2}'::jsonb
from membership_tier t, benefit b where t.code='GOLD' and b.code='EXCLUSIVE_COUPONS';

-- PLATINUM
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"minOrderValue":0}'::jsonb
from membership_tier t, benefit b where t.code='PLATINUM' and b.code='FREE_DELIVERY';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"discountPct":15}'::jsonb
from membership_tier t, benefit b where t.code='PLATINUM' and b.code='EXTRA_DISCOUNT';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{}'::jsonb
from membership_tier t, benefit b where t.code='PLATINUM' and b.code='EXCLUSIVE_DEALS';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{"couponsPerCycle":5}'::jsonb
from membership_tier t, benefit b where t.code='PLATINUM' and b.code='EXCLUSIVE_COUPONS';
insert into tier_benefit (tier_id, benefit_id, params)
select t.id, b.id, '{}'::jsonb
from membership_tier t, benefit b where t.code='PLATINUM' and b.code='PRIORITY_SUPPORT';
