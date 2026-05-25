-- Per-household custom categories. Live alongside the global `categories` table
-- but are private to the household that created them.

CREATE TABLE custom_categories (
    household_id        UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(80)  NOT NULL,
    kind                VARCHAR(16)  NOT NULL CHECK (kind IN ('income','expense')),
    group_code          VARCHAR(32),
    sort_order          INT          NOT NULL DEFAULT 1000,
    essential           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_user_id  UUID         NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, code),
    CHECK ( (kind = 'expense' AND group_code IS NOT NULL) OR
            (kind = 'income'  AND group_code IS NULL) )
);

CREATE INDEX idx_custom_categories_household ON custom_categories(household_id);

-- Drop the existing FKs from transactions/budgets/recurring_templates → categories(code).
-- Custom codes won't satisfy them, and integrity moves into CategoryService.
ALTER TABLE transactions        DROP CONSTRAINT transactions_category_code_fkey;
ALTER TABLE budgets             DROP CONSTRAINT budgets_category_code_fkey;
ALTER TABLE recurring_templates DROP CONSTRAINT recurring_templates_category_code_fkey;

-- Drop the cross-table direction-validation trigger; replaced by CategoryService.requireForDirection().
DROP TRIGGER IF EXISTS trg_transactions_direction ON transactions;
DROP TRIGGER IF EXISTS trg_recurring_direction    ON recurring_templates;
DROP FUNCTION IF EXISTS enforce_transaction_direction();
