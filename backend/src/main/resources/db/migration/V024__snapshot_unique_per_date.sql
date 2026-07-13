-- Enforce one snapshot per (household, date). The app relies on this invariant (auto-snapshot's
-- "already taken today?" check, the "previous = latest strictly before" prefill logic, and the
-- net-worth charts), but nothing at the DB level guaranteed it, so a manual save racing the
-- scheduled job — or two quick manual saves — could create duplicate same-date rows.

-- First collapse any pre-existing duplicates, keeping the most recently created row per
-- (household, date). Child rows (asset values, liability balances, named values) cascade on delete.
DELETE FROM snapshots s
USING snapshots keep
WHERE s.household_id = keep.household_id
  AND s.snapshot_date = keep.snapshot_date
  AND (s.created_at < keep.created_at
       OR (s.created_at = keep.created_at AND s.id < keep.id));

-- Replace the plain lookup index with a UNIQUE one (still serves the ORDER BY snapshot_date DESC
-- scans via a backward index scan).
DROP INDEX IF EXISTS idx_snapshots_household_date;
CREATE UNIQUE INDEX idx_snapshots_household_date_unique ON snapshots(household_id, snapshot_date);
