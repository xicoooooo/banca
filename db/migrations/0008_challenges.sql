-- Challenges.
--
-- A player sets a challenge by playing a short run of blackjack from a shoe
-- shuffled from a seed, and anyone they give its code to plays the same run
-- from the same shoe. Each may play it once. The chips in it are the
-- challenge's own and nobody's bankroll is touched; only how each finished is
-- kept, so that they can be compared.
create table challenges (
    id         uuid        primary key default gen_random_uuid(),
    code       text        not null unique,
    seed       bigint      not null,
    rounds     int         not null,
    stack      bigint      not null,
    created_by uuid        not null references profiles (id) on delete cascade,
    created_at timestamptz not null default now()
);

-- One row for each player who has taken a challenge, made as they sit down,
-- so that it cannot be taken twice. It is finished when they have played every
-- round, run out of chips, or left, with what they ended on.
create table challenge_entries (
    challenge_id  uuid        not null references challenges (id) on delete cascade,
    profile_id    uuid        not null references profiles (id) on delete cascade,
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    rounds_played int         not null default 0,
    chips         bigint,
    primary key (challenge_id, profile_id)
);

-- A player's challenges are looked for from the player.
create index challenge_entries_profile_idx on challenge_entries (profile_id);
create index challenges_created_by_idx on challenges (created_by);

-- As with every other table: the backend connects as the owner and enforces
-- the rules itself, and nothing is open to the public API roles.
alter table challenges enable row level security;
alter table challenge_entries enable row level security;
