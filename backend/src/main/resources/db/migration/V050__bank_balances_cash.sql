-- Linked-bank cash: each account keeps its latest balance (read on the first sync of each day) and
-- whether it counts towards cash; the household keeps a free "other cash" amount on top. The daily
-- bank anchor is written as a cash adjustment with source 'bank' and no author.

ALTER TABLE bank_connection_accounts
    ADD COLUMN balance_amount      NUMERIC(15,2),
    ADD COLUMN balance_currency    VARCHAR(3),
    ADD COLUMN balance_amount_base NUMERIC(15,2),
    ADD COLUMN balance_type        VARCHAR(8),
    ADD COLUMN balance_date        DATE,
    ADD COLUMN balance_fetched_on  DATE,
    ADD COLUMN counts_as_cash      BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE cash_estimate_settings
    ADD COLUMN other_cash NUMERIC(15,2) NOT NULL DEFAULT 0;

ALTER TABLE cash_adjustments
    ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'bank')),
    ALTER COLUMN created_by_user_id DROP NOT NULL,
    ALTER COLUMN updated_by_user_id DROP NOT NULL;
