-- Loans: money the household has loaned out to other people.
-- Tracking-only: not a ledger transaction, not in net worth.
CREATE TABLE loans (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id             UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    borrower_name            VARCHAR(120) NOT NULL,
    principal_amount         NUMERIC(15,2) NOT NULL CHECK (principal_amount > 0),
    start_date               DATE NOT NULL,
    description              VARCHAR(500),
    interest_type            VARCHAR(16) NOT NULL CHECK (interest_type IN ('none','simple','compound')),
    annual_interest_rate     NUMERIC(8,4),
    compounding_period       VARCHAR(16) CHECK (compounding_period IN ('monthly','yearly')),
    status                   VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','settled','written_off')),
    closed_date              DATE,
    created_by_user_id       UUID NOT NULL REFERENCES users(id),
    updated_by_user_id       UUID NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at               TIMESTAMPTZ
);
CREATE INDEX idx_loans_household_active
    ON loans(household_id)
    WHERE deleted_at IS NULL AND status = 'active';
CREATE INDEX idx_loans_household
    ON loans(household_id)
    WHERE deleted_at IS NULL;

CREATE TABLE loan_schedules (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id                     UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    frequency                   VARCHAR(16) NOT NULL CHECK (frequency IN ('weekly','monthly','yearly')),
    day_of_week                 SMALLINT CHECK (day_of_week BETWEEN 1 AND 7),
    day_of_month                SMALLINT CHECK (day_of_month BETWEEN 1 AND 31),
    expected_amount             NUMERIC(15,2) NOT NULL CHECK (expected_amount > 0),
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    last_materialized_through   DATE,
    created_by_user_id          UUID NOT NULL REFERENCES users(id),
    updated_by_user_id          UUID NOT NULL REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_schedules_loan ON loan_schedules(loan_id);
CREATE INDEX idx_loan_schedules_active ON loan_schedules(loan_id) WHERE active = TRUE;

CREATE TABLE loan_payments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id               UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    payment_date          DATE NOT NULL,
    amount                NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description           VARCHAR(500),
    schedule_id           UUID REFERENCES loan_schedules(id) ON DELETE SET NULL,
    created_by_user_id    UUID NOT NULL REFERENCES users(id),
    updated_by_user_id    UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_loan_payments_loan_date ON loan_payments(loan_id, payment_date) WHERE deleted_at IS NULL;

-- Idempotency: scheduler cannot duplicate materialized payments for the same (schedule, date).
CREATE UNIQUE INDEX idx_loan_payments_schedule_date_unique
    ON loan_payments(schedule_id, payment_date)
    WHERE schedule_id IS NOT NULL AND deleted_at IS NULL;
