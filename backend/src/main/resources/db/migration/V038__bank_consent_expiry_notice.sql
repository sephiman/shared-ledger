-- One Telegram notice per consent cycle: set when the expiry notice goes out, cleared by a re-link.
ALTER TABLE bank_connections ADD COLUMN expiry_notified_at TIMESTAMPTZ;

-- The consent-expiry banner pairs a user dismissed, as CSV of "<connectionId>@<expiry date>"; a re-link
-- moves the date, so the banner comes back for the next cycle. Additive; nothing dismissed yet.
ALTER TABLE users ADD COLUMN dismissed_consent_notices TEXT NOT NULL DEFAULT '';
