ALTER TABLE liabilities
    ADD COLUMN counts_toward_net_worth BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE snapshot_liability_balances
    ADD COLUMN counts_toward_net_worth BOOLEAN NOT NULL DEFAULT TRUE;
