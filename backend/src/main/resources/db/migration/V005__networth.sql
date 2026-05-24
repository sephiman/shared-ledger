CREATE TABLE liabilities (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name                  VARCHAR(120) NOT NULL,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_liabilities_household_name_active
    ON liabilities(household_id, name)
    WHERE deleted_at IS NULL;

CREATE TABLE snapshots (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    snapshot_date         DATE NOT NULL,
    note                  VARCHAR(500),
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshots_household_date ON snapshots(household_id, snapshot_date DESC);

CREATE TABLE snapshot_asset_values (
    snapshot_id        UUID NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
    asset_class_code   VARCHAR(32) NOT NULL REFERENCES asset_classes(code),
    value              NUMERIC(15,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_id, asset_class_code)
);

CREATE TABLE snapshot_liability_balances (
    snapshot_id    UUID NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
    liability_id   UUID NOT NULL REFERENCES liabilities(id),
    balance        NUMERIC(15,2) NOT NULL,
    PRIMARY KEY (snapshot_id, liability_id)
);

CREATE TABLE net_worth_movements (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    movement_date         DATE NOT NULL,
    type                  VARCHAR(24) NOT NULL CHECK (type IN ('contribution','withdrawal','debt_payment')),
    asset_class_code      VARCHAR(32) REFERENCES asset_classes(code),
    liability_id          UUID REFERENCES liabilities(id),
    amount                NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description           VARCHAR(500),
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT movement_target_chk CHECK (
        (type IN ('contribution','withdrawal') AND asset_class_code IS NOT NULL AND liability_id IS NULL) OR
        (type = 'debt_payment' AND liability_id IS NOT NULL AND asset_class_code IS NULL)
    )
);
CREATE INDEX idx_mv_household_date ON net_worth_movements(household_id, movement_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_mv_household_class_date ON net_worth_movements(household_id, asset_class_code, movement_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_mv_household_liability_date ON net_worth_movements(household_id, liability_id, movement_date) WHERE deleted_at IS NULL;
