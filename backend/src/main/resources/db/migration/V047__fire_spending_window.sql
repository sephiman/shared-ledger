ALTER TABLE fire_settings
    ADD COLUMN spending_lookback_months SMALLINT NOT NULL DEFAULT 12,
    ADD COLUMN spending_aggregation VARCHAR(16) NOT NULL DEFAULT 'mean',
    ADD COLUMN exclude_current_month BOOLEAN NOT NULL DEFAULT FALSE;
