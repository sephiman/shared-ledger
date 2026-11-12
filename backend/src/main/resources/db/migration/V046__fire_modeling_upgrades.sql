ALTER TABLE fire_settings
    ADD COLUMN index_tax_brackets BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN drawdown_years SMALLINT NOT NULL DEFAULT 30,
    ADD COLUMN cash_flow_events JSONB NOT NULL DEFAULT '[]'::jsonb;
