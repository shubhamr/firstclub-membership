-- ---------------------------------------------------------------------------
-- Referential integrity on user_id.
--
-- This service owns identity as a single monolith (registration-first: a user is provisioned in
-- app_user before it can subscribe or accrue activity), so every user_id is backed by a foreign
-- key to app_user rather than carried as a loose indexed column. On a fresh migrate these tables
-- hold no rows referencing a user (the V16/V20 backfills select from empty source tables), and the
-- write paths now require the user to exist, so no backfill or cleanup is needed here.
-- ---------------------------------------------------------------------------
alter table subscription
    add constraint fk_subscription_user foreign key (user_id) references app_user (id);

alter table subscription_event
    add constraint fk_subscription_event_user foreign key (user_id) references app_user (id);

alter table credit_note
    add constraint fk_credit_note_user foreign key (user_id) references app_user (id);

alter table payment
    add constraint fk_payment_user foreign key (user_id) references app_user (id);

alter table user_order_stats
    add constraint fk_user_order_stats_user foreign key (user_id) references app_user (id);

alter table user_order_event
    add constraint fk_user_order_event_user foreign key (user_id) references app_user (id);

alter table user_monthly_spend
    add constraint fk_user_monthly_spend_user foreign key (user_id) references app_user (id);
