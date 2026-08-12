-- Merging N pending movements into ONE transaction (split card charges, a tip billed separately) makes
-- V032's "a transaction backs at most one movement" false by design, so its unique index has to go. The
-- pair constraint still forbids linking the same movement twice, and Replace keeps its protection from
-- the explicit join-table check in PendingMovementService.replace (which the index only backstopped).
DROP INDEX uq_pending_movement_transaction;

-- That check and the linked-candidate lookup both query by transaction_id; keep it indexed.
CREATE INDEX idx_pending_movement_transaction_txid
    ON pending_movement_transactions(transaction_id);
