-- ---------------------------------------------------------------------------
-- Free trials: a plan may offer a trial window during which no charge is taken.
--
-- Only YEARLY carries a trial, so the MONTHLY-based demos and tests keep exercising the
-- immediate-charge path.
-- ---------------------------------------------------------------------------
alter table membership_plan add column trial_days int not null default 0;
update membership_plan set trial_days = 7 where cadence = 'YEARLY';

-- Non-null only while the subscription is in trial; cleared on conversion to paid, so "is in
-- trial" is a single nullability check rather than a date comparison.
alter table subscription add column trial_end timestamptz;
