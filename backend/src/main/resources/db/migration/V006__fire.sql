CREATE TABLE fire_settings (
    household_id                 UUID PRIMARY KEY REFERENCES households(id) ON DELETE CASCADE,
    target_amount                NUMERIC(15,2) NOT NULL DEFAULT 0,
    target_year                  SMALLINT NOT NULL DEFAULT 2050,
    monthly_contribution         NUMERIC(15,2) NOT NULL DEFAULT 0,
    return_scenarios             JSONB NOT NULL DEFAULT '[4.0, 6.0, 8.0]'::jsonb,
    qualifying_asset_classes     TEXT[] NOT NULL DEFAULT ARRAY['index_funds','etfs','stocks','crypto','pension'],
    updated_by_user_id           UUID REFERENCES users(id),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
