-- Optional scheduled net-worth snapshots, per household. Opt-in (disabled by default).
CREATE TABLE auto_snapshot_settings (
    household_id        UUID PRIMARY KEY REFERENCES households(id) ON DELETE CASCADE,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    frequency           VARCHAR(16) NOT NULL DEFAULT 'monthly'
                            CHECK (frequency IN ('daily','weekly','monthly')),
    updated_by_user_id  UUID REFERENCES users(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A scheduled snapshot carries the manual asset classes forward from the previous
-- snapshot; those values are flagged 'carried_over' so the UI can distinguish them
-- from fresh portfolio ('computed') and user-entered ('overridden') values.
ALTER TABLE snapshot_asset_values DROP CONSTRAINT snapshot_asset_values_value_source_check;
ALTER TABLE snapshot_asset_values ADD CONSTRAINT snapshot_asset_values_value_source_check
    CHECK (value_source IN ('computed','overridden','carried_over'));
