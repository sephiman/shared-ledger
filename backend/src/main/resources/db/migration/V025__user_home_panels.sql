-- Per-user Home panel visibility: CSV of hidden panel ids (see HomePanel enum).
-- Storing the hidden set keeps the default (all panels visible) as the empty string,
-- and makes any panel added later visible without a backfill.
ALTER TABLE users
    ADD COLUMN hidden_home_panels TEXT NOT NULL DEFAULT '';
