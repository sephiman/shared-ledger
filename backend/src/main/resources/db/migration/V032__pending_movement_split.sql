-- Splitting a bank movement into several transactions breaks the one-item-one-transaction assumption
-- behind pending_movements.created_transaction_id. This table records every transaction a pending item
-- produced — splits and ordinary confirms alike — so "is this transaction already resolving a movement?"
-- has one place to look. created_transaction_id keeps its meaning and stays NULL for splits.
CREATE TABLE pending_movement_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pending_movement_id UUID NOT NULL REFERENCES pending_movements(id) ON DELETE CASCADE,
    -- No FK, as created_transaction_id: transactions are soft-deleted, and the household wipe hard-deletes
    -- them before pending movements. The cascade above is what keeps this table clean.
    transaction_id      UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pending_movement_transaction_pair UNIQUE (pending_movement_id, transaction_id)
);

-- A transaction backs at most one movement: the Replace guard's rule, now enforced by the database too.
CREATE UNIQUE INDEX uq_pending_movement_transaction
    ON pending_movement_transactions(transaction_id);

-- Backfill the single-link history. DISTINCT ON so legacy data violating the rule above can't fail the
-- migration; the losing row just keeps its created_transaction_id, as today.
INSERT INTO pending_movement_transactions (pending_movement_id, transaction_id)
SELECT DISTINCT ON (created_transaction_id) id, created_transaction_id
FROM pending_movements
WHERE created_transaction_id IS NOT NULL
ORDER BY created_transaction_id, processed_at NULLS LAST, id;
