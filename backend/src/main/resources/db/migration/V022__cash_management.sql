-- Cash management: a dated adjustment series (source of truth for cash, like an asset's value
-- history) plus per-household toggles for which flow types feed the on-demand cash estimate.
-- Cash stays an aggregate asset class ('cash' in snapshot_asset_values); this adds the series and
-- estimate behind it. Enum-like/boolean columns are validated in the service layer (no CHECK).

CREATE TABLE cash_adjustments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    adjustment_date       DATE NOT NULL,
    amount                NUMERIC(15,2) NOT NULL,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_cash_adjustments_household_date
    ON cash_adjustments(household_id, adjustment_date DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE cash_estimate_settings (
    household_id          UUID PRIMARY KEY REFERENCES households(id) ON DELETE CASCADE,
    include_transactions  BOOLEAN NOT NULL DEFAULT TRUE,
    include_lendings      BOOLEAN NOT NULL DEFAULT TRUE,
    include_movements     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by_user_id    UUID REFERENCES users(id),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
