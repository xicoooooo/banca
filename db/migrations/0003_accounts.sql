-- Signing in, so a profile can be reached from more than one device.
--
-- A profile used to be known by a single token, which tied it to the one
-- browser that held it. Now a profile may have many tokens, one for each
-- device it is open on, and may belong to an account that can ask for more.

-- The account a profile is saved to: the user's id at the sign-in provider.
-- Null for a guest. One account has one profile.
alter table profiles
    add column auth_user_id uuid unique;

-- What a device shows to prove which profile is its own. Only the hash of the
-- token is kept, so reading this table gives nobody the means to be a player.
create table player_tokens (
    token_hash text primary key,
    profile_id uuid        not null references profiles (id) on delete cascade,
    created_at timestamptz not null default now()
);

create index player_tokens_profile_idx on player_tokens (profile_id);

-- Only the backend reads this, as with every other table here.
alter table player_tokens enable row level security;

insert into player_tokens (token_hash, profile_id)
select token_hash, id
from profiles
where token_hash is not null;

alter table profiles
    drop column token_hash;
