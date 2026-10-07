-- Weekly leagues for players who have signed in.
--
-- A league is a tier a player holds from week to week. Each week the players
-- in a tier are ranked by what they won at the tables, and when the week is
-- over some go up, some go down, and the top few are paid a prize. What a
-- week came to for each player is kept, so they can be told how it went.

-- Chips paid for finishing near the top of a league. A reason of its own, so
-- the ledger says where they came from.
alter type wallet_reason add value if not exists 'league_prize';

-- The league a player is in: 0 is the lowest. Everyone starts there.
alter table profiles
    add column league_tier int not null default 0 check (league_tier >= 0);

-- A week that has been settled, named by the Monday it began on. A week is
-- settled once, by whichever request first notices it is over, and the row
-- here is what stops it being settled twice.
create table league_weeks (
    week_start date primary key,
    settled_at timestamptz not null default now()
);

-- What a settled week came to for one player.
create table league_results (
    week_start date   not null references league_weeks (week_start) on delete cascade,
    profile_id uuid   not null references profiles (id) on delete cascade,
    -- The tier they played the week in, and where they finished in it.
    tier       int    not null,
    position   int    not null,
    net        bigint not null,
    rounds     int    not null,
    outcome    text   not null check (outcome in ('promoted', 'stayed', 'demoted')),
    prize      bigint not null default 0,
    primary key (week_start, profile_id)
);

create index league_results_profile_idx on league_results (profile_id, week_start desc);

-- Standings are worked out from rounds by when they ended.
create index rounds_ended_idx on rounds (ended_at);

-- Only the backend reads these, as with every other table here.
alter table league_weeks enable row level security;
alter table league_results enable row level security;
