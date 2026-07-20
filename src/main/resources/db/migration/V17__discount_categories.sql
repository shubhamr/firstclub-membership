-- ---------------------------------------------------------------------------
-- Scope the extra-discount perk to categories, widening with tier rank.
--
-- Scope is data, not schema: tier_benefit.params is free-form jsonb and ResolvedBenefit passes it
-- to checkout untouched, so restricting the discount to selected categories takes no Java change.
-- Widening the category list per rank is also how "each tier unlocks more" is expressed here —
-- as tuning of one benefit rather than as additional benefit rows.
--
-- Concatenating (||) rather than assigning (=) so discountPct survives. An assignment would drop
-- the percentage and leave the perk inert.
-- ---------------------------------------------------------------------------

update tier_benefit tb
set params = tb.params || '{"categories": ["FASHION"]}'::jsonb
from benefit b, membership_tier t
where tb.benefit_id = b.id
  and tb.tier_id = t.id
  and b.code = 'EXTRA_DISCOUNT'
  and t.code = 'SILVER';

update tier_benefit tb
set params = tb.params || '{"categories": ["FASHION", "ELECTRONICS"]}'::jsonb
from benefit b, membership_tier t
where tb.benefit_id = b.id
  and tb.tier_id = t.id
  and b.code = 'EXTRA_DISCOUNT'
  and t.code = 'GOLD';

update tier_benefit tb
set params = tb.params || '{"categories": ["FASHION", "ELECTRONICS", "HOME", "GROCERY"]}'::jsonb
from benefit b, membership_tier t
where tb.benefit_id = b.id
  and tb.tier_id = t.id
  and b.code = 'EXTRA_DISCOUNT'
  and t.code = 'PLATINUM';
