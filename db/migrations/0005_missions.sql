-- Daily missions.
--
-- Three small things to do at the tables each day, and a few chips for each
-- one done. Which missions a player has, and how far along they are, is worked
-- out from the day and from the rounds they have played, so nothing about that
-- is stored. All that is kept is the payment, in the ledger like any other.

-- Chips paid for a mission. A reason of its own, so the ledger says where they
-- came from. Which mission, and on which day, is the entry's ref_id.
alter type wallet_reason add value if not exists 'mission_reward';

-- A mission is paid for once. The row for it is found by its reference, and
-- this keeps that quick however long the ledger grows.
create index if not exists wallet_entries_reference_idx on wallet_entries (profile_id, reason, ref_id) where ref_id is not null;
