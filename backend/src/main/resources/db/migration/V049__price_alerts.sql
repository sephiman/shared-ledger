CREATE TABLE price_alerts (
    id                       UUID PRIMARY KEY,
    household_id             UUID           NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    holding_id               UUID           NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
    direction                VARCHAR(8)     NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    threshold                NUMERIC(28,12) NOT NULL CHECK (threshold > 0),
    currency                 VARCHAR(3)     NOT NULL,
    expires_at               TIMESTAMPTZ,
    note                     VARCHAR(200),
    status                   VARCHAR(16)    NOT NULL CHECK (status IN ('ACTIVE', 'FIRED', 'EXPIRED', 'CANCELLED')),
    fired_at                 TIMESTAMPTZ,
    fired_price              NUMERIC(28,12),
    fired_price_as_of        TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancel_reason            VARCHAR(24)    CHECK (cancel_reason IN ('USER', 'POSITION_CLOSED')),
    delivery_sent_at         TIMESTAMPTZ,
    delivery_attempts        INT            NOT NULL DEFAULT 0,
    delivery_last_attempt_at TIMESTAMPTZ,
    delivery_last_error      TEXT,
    created_by_user_id       UUID           NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_alerts_household_status
    ON price_alerts (household_id, status, created_at DESC);

CREATE INDEX idx_price_alerts_holding_active
    ON price_alerts (holding_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_price_alerts_pending_delivery
    ON price_alerts (household_id)
    WHERE status = 'FIRED' AND delivery_sent_at IS NULL;
