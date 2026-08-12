-- Refunds are negative expenses: money coming back for a past purchase is an expense transaction with a
-- negative amount, dated when it returned and optionally linked to the original. That nets the category
-- and the month the money actually came back without ever rewriting the original purchase, and — since
-- every aggregation sums signed amounts by direction — it flows through totals, budgets, savings rate and
-- the FIRE spending base on its own.
ALTER TABLE transactions ADD COLUMN is_refund BOOLEAN NOT NULL DEFAULT FALSE;

-- No FK, as pending_movement_transactions.transaction_id (V032): transactions are soft-deleted, which an
-- FK cannot see, and the household wipe hard-deletes them with one raw DELETE. Existence, ownership and
-- "the original is not itself a refund" are validated in TransactionService.
ALTER TABLE transactions ADD COLUMN refund_of_transaction_id UUID;

-- V004's inline CHECK (amount > 0) is what made a refund impossible; it becomes conditional rather than
-- disappearing, so an ordinary transaction still can't be stored negative by accident.
ALTER TABLE transactions DROP CONSTRAINT transactions_amount_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_amount_check
    CHECK ((is_refund AND amount < 0) OR (NOT is_refund AND amount > 0));

-- Refunding income is out of scope: returning income is just an ordinary expense.
ALTER TABLE transactions ADD CONSTRAINT transactions_refund_expense_check
    CHECK (NOT is_refund OR direction = 'expense');

-- "Which live refunds point at this original", for the netted-total badge.
CREATE INDEX idx_tx_refund_of ON transactions(refund_of_transaction_id)
    WHERE refund_of_transaction_id IS NOT NULL AND deleted_at IS NULL;
