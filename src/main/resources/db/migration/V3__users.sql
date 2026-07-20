-- ---------------------------------------------------------------------------
-- User directory, plus demo users.
--
-- Membership references user_id without a foreign key. That keeps identity loosely coupled: in
-- production this table is a projection of an external identity service, and a subscription must
-- remain valid whether or not the local projection has caught up.
-- ---------------------------------------------------------------------------
create table app_user (
    id         bigint       primary key,
    name       varchar(128) not null,
    email      varchar(255) not null unique,
    cohort     varchar(32),
    created_at timestamptz  not null default now()
);

insert into app_user (id, name, email, cohort) values
    (7001, 'Aarav Shah',  'aarav@firstclub.test', null),
    (7002, 'Priya Nair',  'priya@firstclub.test', 'PREMIER'),
    (7003, 'Rohan Verma', 'rohan@firstclub.test', 'VIP'),
    (7004, 'Meera Iyer',  'meera@firstclub.test', null);
