-- The "replace a possible duplicate" action checks whether a candidate transaction is already linked to a
-- confirmed movement (a transaction backs at most one). Partial: only confirmed rows carry a value.
CREATE INDEX idx_pending_created_transaction
    ON pending_movements(created_transaction_id)
    WHERE created_transaction_id IS NOT NULL;
