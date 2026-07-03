-- Per-household toggle for portfolio trade (buy/sell) notifications.
ALTER TABLE telegram_settings
    ADD COLUMN notify_holdings BOOLEAN NOT NULL DEFAULT TRUE;
