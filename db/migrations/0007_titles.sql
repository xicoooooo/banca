-- Titles.
--
-- A player may wear one of the titles they have earned, and the others at a
-- shared table see it beside their name. Which titles a player has earned is
-- worked out from their record and never stored; only the choice is kept here,
-- as the words themselves. Null means they wear none.
alter table profiles
    add column title text;
