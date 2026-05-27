-- Per-household Telegram notification settings. One row per household.
-- The bot token is stored encrypted (AES-GCM ciphertext, base64) and is never
-- returned to clients. All toggles default to enabled so that, once a household
-- configures a token + chat id and flips the master switch on, notifications flow
-- without further setup.
CREATE TABLE telegram_settings (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id              UUID NOT NULL UNIQUE REFERENCES households(id) ON DELETE CASCADE,
    active                    BOOLEAN NOT NULL DEFAULT TRUE,
    notify_transactions       BOOLEAN NOT NULL DEFAULT TRUE,
    notify_snapshots          BOOLEAN NOT NULL DEFAULT TRUE,
    notify_movements          BOOLEAN NOT NULL DEFAULT TRUE,
    notify_loan_payments      BOOLEAN NOT NULL DEFAULT TRUE,
    notify_recurring_txn      BOOLEAN NOT NULL DEFAULT TRUE,
    notify_recurring_loan     BOOLEAN NOT NULL DEFAULT TRUE,
    chat_id                   VARCHAR(64),
    bot_token_enc             TEXT,
    created_by_user_id        UUID NOT NULL REFERENCES users(id),
    updated_by_user_id        UUID NOT NULL REFERENCES users(id),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
