-- Persisted AI spending reviews and follow-up Q&A per household.
-- Each run snapshots the sanitized pre-computed analytics payload (payload_json),
-- a local-only id -> display name map (local_labels_json) so anonymized counterparties
-- render with real names in the household UI without ever leaving the instance, and the
-- verified structured report (result_json).

CREATE TABLE analytics_ai_review_runs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id              UUID         REFERENCES users(id) ON DELETE SET NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'running'
                                      CHECK (status IN ('running', 'completed', 'failed', 'interrupted')),
    model                VARCHAR(128) NOT NULL,
    prompt_version       VARCHAR(32)  NOT NULL,
    locale               VARCHAR(2)   NOT NULL,
    period_year          INT          NOT NULL,
    period_month         INT          NOT NULL,
    review_kind          VARCHAR(24)  NOT NULL CHECK (review_kind IN ('complete_month', 'pace_review')),
    days_elapsed         INT          NOT NULL,
    days_in_month        INT          NOT NULL,
    stale_spending_since VARCHAR(7),
    payload_json         JSONB        NOT NULL,
    local_labels_json    JSONB        NOT NULL DEFAULT '{}',
    result_json          JSONB,
    findings_dropped     INT,
    citations_total      INT,
    citations_ungrounded INT,
    input_tokens         INT,
    output_tokens        INT,
    reasoning_tokens     INT,
    latency_ms           BIGINT,
    error_code           VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at          TIMESTAMPTZ
);

CREATE INDEX idx_analytics_ai_review_runs_household ON analytics_ai_review_runs (household_id, created_at DESC);
-- At most one analytics review in progress per household at a time.
CREATE UNIQUE INDEX idx_analytics_ai_review_runs_one_running ON analytics_ai_review_runs (household_id) WHERE status = 'running';

CREATE TABLE analytics_ai_review_followups (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id               UUID        NOT NULL REFERENCES analytics_ai_review_runs(id) ON DELETE CASCADE,
    household_id         UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id              UUID        REFERENCES users(id) ON DELETE SET NULL,
    question             TEXT        NOT NULL,
    answer               TEXT        NOT NULL,
    citations_json       JSONB       NOT NULL DEFAULT '[]',
    citations_total      INT         NOT NULL DEFAULT 0,
    citations_ungrounded INT         NOT NULL DEFAULT 0,
    input_tokens         INT,
    output_tokens        INT,
    reasoning_tokens     INT,
    latency_ms           BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analytics_ai_review_followups_run ON analytics_ai_review_followups (run_id, created_at ASC);
