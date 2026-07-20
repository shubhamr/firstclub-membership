-- Optimistic-lock column for job_cursor, so a concurrent advance from a second instance conflicts
-- rather than silently overwriting a higher cursor with a lower value. Same @Version primitive as
-- subscription and payment.
alter table job_cursor add column version bigint not null default 0;
