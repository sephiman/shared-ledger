-- Named assets & liabilities with their own dated value series (source of truth),
-- and the snapshot child rows that freeze named-asset values per snapshot.
-- Enum-like columns (asset type) are validated in the service layer, not by CHECK constraints.

CREATE TABLE assets (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name                  VARCHAR(120) NOT NULL,
    type                  VARCHAR(24) NOT NULL DEFAULT 'other',
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_assets_household_name_active
    ON assets(household_id, name)
    WHERE deleted_at IS NULL;

CREATE TABLE asset_value_entries (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    value_date            DATE NOT NULL,
    value                 NUMERIC(15,2) NOT NULL,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_asset_value_entries_asset_date
    ON asset_value_entries(asset_id, value_date DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE liability_balance_entries (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    liability_id          UUID NOT NULL REFERENCES liabilities(id) ON DELETE CASCADE,
    balance_date          DATE NOT NULL,
    balance               NUMERIC(15,2) NOT NULL,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_liability_balance_entries_liability_date
    ON liability_balance_entries(liability_id, balance_date DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE snapshot_named_asset_values (
    snapshot_id    UUID NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
    asset_id       UUID NOT NULL REFERENCES assets(id),
    value          NUMERIC(15,2) NOT NULL,
    PRIMARY KEY (snapshot_id, asset_id)
);
