-- Confirming a pending bank movement can now produce a net-worth movement instead of a transaction
-- (e.g. a transfer to savings or a debt principal payment shouldn't land in the income/expense
-- ledger). This records which net-worth movement a confirmed item generated, mirroring
-- created_transaction_id. Additive and nullable — every existing pending/confirmed row is untouched
-- and keeps its created_transaction_id.
ALTER TABLE pending_movements ADD COLUMN created_movement_id UUID;
