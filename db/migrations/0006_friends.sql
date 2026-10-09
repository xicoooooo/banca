-- Friends.
--
-- Players who have signed in can be friends with one another: one asks, the
-- other accepts. Who is online is not kept here. The server knows that from
-- who is connected to it, and forgets it when it stops.

-- A short code a player can give out so that someone can ask to be their
-- friend. Display names are not unique and a profile's id is not something to
-- read out, so this is what is said or typed. Made the first time it is wanted.
alter table profiles
    add column friend_code text unique;

-- One row for each pair, in the direction it was asked. It is a request until
-- accepted_at is set, and a friendship after. Either of the two may end it,
-- which deletes the row.
create table friendships (
    requester_id uuid        not null references profiles (id) on delete cascade,
    addressee_id uuid        not null references profiles (id) on delete cascade,
    created_at   timestamptz not null default now(),
    accepted_at  timestamptz,
    primary key (requester_id, addressee_id),
    check (requester_id <> addressee_id)
);

-- A player's friendships are looked for from both ends.
create index friendships_addressee_idx on friendships (addressee_id);

-- As with every other table: the backend connects as the owner and enforces
-- the rules itself, and nothing is open to the public API roles.
alter table friendships enable row level security;
