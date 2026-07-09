-- Background bank sync backoff. When an unattended (background) fetch hits the ASPSP's per-consent
-- daily rate limit (ASPSP_RATE_LIMIT_EXCEEDED), the connection records when it may retry; the
-- scheduler skips it until then. Additive and nullable — existing connections, movements, cursors
-- and consents are untouched.
ALTER TABLE bank_connections ADD COLUMN sync_backoff_until TIMESTAMPTZ;
