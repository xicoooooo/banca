-- Banca initial schema.
--
-- Two rules shape this file:
--   1. The wallet is an append-only ledger. Nothing stores a balance; a balance
--      is the sum of a player's entries, so it can always be explained.
--   2. Clients never reach the database. The backend connects with the service
--      role and enforces every rule itself, so row level security is enabled
--      with no policies: anon and authenticated keys can read nothing.

create extension if not exists pgcrypto;

-- Players -------------------------------------------------------------------

-- On Supabase, id matches auth.users(id). No foreign key is declared here so
-- the schema also applies to a plain Postgres instance.
create table profiles (
    id           uuid primary key,
    display_name text        not null,
    avatar_seed  text        not null,
    level        int         not null default 1,
    xp           bigint      not null default 0,
    created_at   timestamptz not null default now()
);

-- Chips ---------------------------------------------------------------------

create type wallet_reason as enum ('round', 'daily_reward', 'bust_top_up', 'signup_grant');

create table wallet_entries (
    id         bigserial primary key,
    profile_id uuid          not null references profiles (id) on delete cascade,
    -- Negative for chips leaving the player, positive for chips arriving.
    amount     bigint        not null,
    reason     wallet_reason not null,
    -- What caused it: a round id, a daily claim date, and so on.
    ref_id     text,
    created_at timestamptz   not null default now()
);

create index wallet_entries_profile_idx on wallet_entries (profile_id, created_at desc);

create view wallet_balances as
select profile_id,
       coalesce(sum(amount), 0) as balance
from wallet_entries
group by profile_id;

-- Play ----------------------------------------------------------------------

create type game_kind as enum ('poker', 'blackjack', 'roulette');

create table rounds (
    id         uuid primary key default gen_random_uuid(),
    game       game_kind   not null,
    table_id   text        not null,
    started_at timestamptz not null default now(),
    ended_at   timestamptz
);

create index rounds_table_idx on rounds (table_id, started_at desc);

create type actor_kind as enum ('human', 'agent');

create table round_actions (
    id         bigserial primary key,
    round_id   uuid        not null references rounds (id) on delete cascade,
    seat       int         not null,
    actor      actor_kind  not null,
    -- Null when the actor is an agent.
    profile_id uuid references profiles (id) on delete set null,
    -- The action itself, plus the agent's reasoning trace when there is one.
    detail     jsonb       not null,
    created_at timestamptz not null default now()
);

create index round_actions_round_idx on round_actions (round_id, id);

create table round_results (
    id         bigserial primary key,
    round_id   uuid   not null references rounds (id) on delete cascade,
    seat       int    not null,
    profile_id uuid references profiles (id) on delete set null,
    net_chips  bigint not null,
    detail     jsonb,
    unique (round_id, seat)
);

create index round_results_profile_idx on round_results (profile_id);

-- Progression ---------------------------------------------------------------

-- One row per player per server day, so a daily reward can never be paid twice.
create table daily_claims (
    profile_id uuid        not null references profiles (id) on delete cascade,
    claim_date date        not null,
    streak_day int         not null check (streak_day between 1 and 7),
    chips      bigint      not null,
    created_at timestamptz not null default now(),
    primary key (profile_id, claim_date)
);

-- Lock everything down ------------------------------------------------------

alter table profiles       enable row level security;
alter table wallet_entries enable row level security;
alter table rounds         enable row level security;
alter table round_actions  enable row level security;
alter table round_results  enable row level security;
alter table daily_claims   enable row level security;
