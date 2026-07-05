-- Bank ingestion (PSD2 / Enable Banking). Read-only account information synced into a review
-- inbox; confirming a pending movement generates a normal transaction. Enum-like columns are
-- plain text validated in the service layer (no CHECK constraints), consistent with the rest of
-- the domain. All household-scoped tables cascade on household delete.

-- A linked authorization. Identity = provider + this specific authorization, so a household can
-- hold several connections to the same bank (e.g. two Wise accounts). The session id is stored
-- AES-GCM encrypted (see BankCrypto); it is never returned by the API.
CREATE TABLE bank_connections (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    provider            VARCHAR(24) NOT NULL DEFAULT 'enable_banking',
    aspsp_name          VARCHAR(120) NOT NULL,
    aspsp_country       VARCHAR(2) NOT NULL,
    label               VARCHAR(120),
    holder_user_id      UUID REFERENCES users(id),
    session_id_enc      TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    consent_expires_at  TIMESTAMPTZ,
    last_synced_at      TIMESTAMPTZ,
    ingestion_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    sync_frequency      VARCHAR(16) NOT NULL DEFAULT 'twice_daily',
    -- Per-consent unattended-access budget (PSD2 caps at ~4/day); reset when the date rolls.
    calls_used_today    INTEGER NOT NULL DEFAULT 0,
    calls_reset_on      DATE,
    created_by_user_id  UUID REFERENCES users(id),
    updated_by_user_id  UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_connections_household ON bank_connections(household_id);

-- Accounts exposed by a connection's session (one connection -> many accounts).
CREATE TABLE bank_connection_accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id  UUID NOT NULL REFERENCES bank_connections(id) ON DELETE CASCADE,
    account_uid    VARCHAR(128) NOT NULL,
    iban_masked    VARCHAR(64),
    name           VARCHAR(120),
    currency       VARCHAR(3),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_account_uid UNIQUE (connection_id, account_uid)
);
CREATE INDEX idx_bank_accounts_connection ON bank_connection_accounts(connection_id);

-- The review inbox: raw bank movements awaiting confirm/edit/reject. Separate from transactions;
-- confirming links to the generated transaction so it is never re-ingested. Dedup identity is
-- (connection_id, bank_movement_id) — a bank id is only unique within its connection.
CREATE TABLE pending_movements (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id             UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    connection_id            UUID NOT NULL REFERENCES bank_connections(id) ON DELETE CASCADE,
    account_id               UUID NOT NULL REFERENCES bank_connection_accounts(id) ON DELETE CASCADE,
    bank_movement_id         VARCHAR(255) NOT NULL,
    booking_date             DATE NOT NULL,
    value_date               DATE,
    direction                VARCHAR(16) NOT NULL,
    -- Amount in the household base currency (EUR); non-EUR movements are converted on ingest.
    amount                   NUMERIC(15,2) NOT NULL,
    original_amount          NUMERIC(18,2),
    original_currency        VARCHAR(3),
    counterparty             VARCHAR(255),
    description              VARCHAR(500),
    reference                VARCHAR(255),
    status                   VARCHAR(16) NOT NULL DEFAULT 'pending',
    suggested_category_code  VARCHAR(64),
    created_transaction_id   UUID,
    processed_at             TIMESTAMPTZ,
    processed_by_user_id     UUID REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pending_movement_bank_id UNIQUE (connection_id, bank_movement_id)
);
CREATE INDEX idx_pending_movements_household_status ON pending_movements(household_id, status);
CREATE INDEX idx_pending_movements_connection ON pending_movements(connection_id);

-- Per-connection sync audit (like TradeLog's sync_runs): success/error + new-movement count.
CREATE TABLE bank_sync_runs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id  UUID NOT NULL REFERENCES bank_connections(id) ON DELETE CASCADE,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(16) NOT NULL,
    new_movements  INTEGER NOT NULL DEFAULT 0,
    error_code     VARCHAR(64),
    error_message  VARCHAR(500)
);
CREATE INDEX idx_bank_sync_runs_connection ON bank_sync_runs(connection_id, started_at DESC);

-- Categorisation rules (counterparty/description/amount -> category + direction). Rules with
-- source='learned' are remembered automatically when a movement is confirmed with a category.
CREATE TABLE bank_categorization_rules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    match_field         VARCHAR(16) NOT NULL,
    match_op            VARCHAR(16) NOT NULL,
    match_value         VARCHAR(255) NOT NULL,
    category_code       VARCHAR(64) NOT NULL,
    direction           VARCHAR(16) NOT NULL,
    priority            INTEGER NOT NULL DEFAULT 100,
    source              VARCHAR(16) NOT NULL DEFAULT 'manual',
    created_by_user_id  UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_rules_household ON bank_categorization_rules(household_id, priority);

-- Short-lived OAuth handoff: ties the redirect `state` back to the household/holder that started
-- the link, so a callback cannot bind an authorization to the wrong household.
CREATE TABLE bank_auth_sessions (
    state                 VARCHAR(64) PRIMARY KEY,
    household_id          UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    holder_user_id        UUID REFERENCES users(id),
    aspsp_name            VARCHAR(120) NOT NULL,
    aspsp_country         VARCHAR(2) NOT NULL,
    label                 VARCHAR(120),
    relink_connection_id  UUID REFERENCES bank_connections(id) ON DELETE CASCADE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-household toggles for the two new notification kinds.
ALTER TABLE telegram_settings
    ADD COLUMN notify_bank_movements BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_bank_connections BOOLEAN NOT NULL DEFAULT TRUE;
