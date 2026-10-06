-- Player identity and what a round meant to the player in it.
--
-- A round belongs to a game session: the table, the cards, the dealer. What it
-- did to one player, their stake, their result and the detail of their play,
-- belongs to that player's row in round_results. Keeping the two apart is what
-- lets several players share one round later without the statistics changing
-- shape.

-- Guest identity ------------------------------------------------------------

-- A browser proves which profile is its own with a secret token. Only a hash
-- of it is kept, so reading this table gives nobody the means to be a player.
-- Null for a profile that signs in some other way, when accounts exist.
alter table profiles
    add column token_hash text unique,
    alter column id set default gen_random_uuid();

-- Level and experience are worked out from the rounds a player has played, so
-- they cannot drift from the record. The avatar is drawn from the name.
alter table profiles
    drop column level,
    drop column xp,
    drop column avatar_seed;

-- What the round did to the player ------------------------------------------

alter table round_results
    -- Chips the player put at risk in the round.
    add column staked  bigint not null default 0,
    -- 'win', 'loss' or 'push', so results can be counted without knowing the game.
    add column outcome text   not null default 'push' check (outcome in ('win', 'loss', 'push'));

alter table round_results
    alter column staked drop default,
    alter column outcome drop default;

-- A player's history is always read newest first.
create index round_results_recent_idx on round_results (profile_id, id desc);
