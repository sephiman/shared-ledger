-- Enable Banking credentials move from instance-wide env vars to per-household configuration
-- (Settings -> Banks), following the telegram_settings pattern. Additive only.

CREATE TABLE bank_credentials (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        UUID NOT NULL UNIQUE REFERENCES households(id) ON DELETE CASCADE,
    -- The API application id (the JWT `kid`). Not a secret: shown back in the form and compared
    -- against bank_connections.app_id to detect connections that need re-linking.
    app_id              VARCHAR(128) NOT NULL,
    private_key_enc     TEXT NOT NULL,
    created_by_user_id  UUID NOT NULL REFERENCES users(id),
    updated_by_user_id  UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The bank ties a consent to one application, so syncing under a different one is impossible.
ALTER TABLE bank_connections ADD COLUMN app_id VARCHAR(128);

-- `credentials_required` / `credentials_mismatch` don't fit the original 16 chars.
ALTER TABLE bank_connections ALTER COLUMN status TYPE VARCHAR(32);

-- The upgrade bridge: the old env var is still present at deploy time, so existing connections get
-- stamped with the application they were authorized under. An owner then pastes that same
-- application into Settings -> Banks and nothing needs re-linking. Rows left NULL (env app id
-- absent) cannot be attributed and ask for a re-link rather than being silently re-routed.
UPDATE bank_connections SET app_id = NULLIF('${ebAppId}', '') WHERE app_id IS NULL;
