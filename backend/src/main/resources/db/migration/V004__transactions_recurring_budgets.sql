CREATE TABLE recurring_templates (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id                UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    direction                   VARCHAR(16) NOT NULL CHECK (direction IN ('income','expense')),
    category_code               VARCHAR(64) NOT NULL REFERENCES categories(code),
    amount                      NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description                 VARCHAR(255),
    cadence                     VARCHAR(16) NOT NULL CHECK (cadence IN ('weekly','monthly','yearly')),
    day_of_month                SMALLINT CHECK (day_of_month BETWEEN 1 AND 31),
    day_of_week                 SMALLINT CHECK (day_of_week BETWEEN 1 AND 7),
    month_of_year               SMALLINT CHECK (month_of_year BETWEEN 1 AND 12),
    day_of_month_yearly         SMALLINT CHECK (day_of_month_yearly BETWEEN 1 AND 31),
    start_date                  DATE NOT NULL,
    end_date                    DATE,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    last_materialized_through   DATE,
    created_by_user_id          UUID NOT NULL REFERENCES users(id),
    updated_by_user_id          UUID NOT NULL REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT recurring_cadence_fields_chk CHECK (
        (cadence = 'weekly'  AND day_of_week IS NOT NULL AND day_of_month IS NULL AND month_of_year IS NULL AND day_of_month_yearly IS NULL) OR
        (cadence = 'monthly' AND day_of_month IS NOT NULL AND day_of_week IS NULL AND month_of_year IS NULL AND day_of_month_yearly IS NULL) OR
        (cadence = 'yearly'  AND month_of_year IS NOT NULL AND day_of_month_yearly IS NOT NULL AND day_of_week IS NULL AND day_of_month IS NULL)
    )
);
CREATE INDEX idx_recurring_household_active ON recurring_templates(household_id) WHERE deleted_at IS NULL AND active = TRUE;

CREATE TABLE transactions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id             UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    occurrence_date          DATE NOT NULL,
    direction                VARCHAR(16) NOT NULL CHECK (direction IN ('income','expense')),
    category_code            VARCHAR(64) NOT NULL REFERENCES categories(code),
    amount                   NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description              VARCHAR(500),
    recurring_template_id    UUID REFERENCES recurring_templates(id),
    created_by_user_id       UUID NOT NULL REFERENCES users(id),
    updated_by_user_id       UUID NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at               TIMESTAMPTZ
);

-- Enforce direction matches the category's kind via trigger (cross-table check)
CREATE OR REPLACE FUNCTION enforce_transaction_direction()
RETURNS TRIGGER AS $$
DECLARE
    cat_kind VARCHAR(16);
BEGIN
    SELECT kind INTO cat_kind FROM categories WHERE code = NEW.category_code;
    IF cat_kind IS NULL THEN
        RAISE EXCEPTION 'CATEGORY_NOT_FOUND';
    END IF;
    IF cat_kind <> NEW.direction THEN
        RAISE EXCEPTION 'CATEGORY_DIRECTION_MISMATCH';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_direction
    BEFORE INSERT OR UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_transaction_direction();

CREATE TRIGGER trg_recurring_direction
    BEFORE INSERT OR UPDATE ON recurring_templates
    FOR EACH ROW EXECUTE FUNCTION enforce_transaction_direction();

CREATE INDEX idx_tx_household_date ON transactions(household_id, occurrence_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_tx_household_cat_date ON transactions(household_id, category_code, occurrence_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_tx_template ON transactions(recurring_template_id) WHERE deleted_at IS NULL;

-- Idempotency: scheduler cannot duplicate materialized rows
CREATE UNIQUE INDEX idx_tx_template_occurrence_unique
    ON transactions(recurring_template_id, occurrence_date)
    WHERE recurring_template_id IS NOT NULL;

CREATE TABLE budgets (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    year                  SMALLINT NOT NULL CHECK (year BETWEEN 1900 AND 9999),
    month                 SMALLINT CHECK (month BETWEEN 1 AND 12),
    category_code         VARCHAR(64) NOT NULL REFERENCES categories(code),
    amount                NUMERIC(15,2) NOT NULL CHECK (amount >= 0),
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_budgets_monthly_unique
    ON budgets(household_id, year, month, category_code)
    WHERE month IS NOT NULL;
CREATE UNIQUE INDEX idx_budgets_annual_unique
    ON budgets(household_id, year, category_code)
    WHERE month IS NULL;
