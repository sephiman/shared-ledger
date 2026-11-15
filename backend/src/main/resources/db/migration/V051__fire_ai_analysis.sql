-- Persisted AI FIRE plan reviews and follow-up Q&A per household.
-- Each run snapshots the aggregated FIRE metrics (payload_json) and the structured review
-- (result_json) so past analyses remain comparable via the evolution summary.

CREATE TABLE fire_ai_analysis_runs (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id              UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id                   UUID         REFERENCES users(id) ON DELETE SET NULL,
    status                    VARCHAR(16)  NOT NULL DEFAULT 'running'
                                           CHECK (status IN ('running', 'completed', 'failed', 'interrupted')),
    model                     VARCHAR(128) NOT NULL,
    prompt_version            VARCHAR(32)  NOT NULL,
    locale                    VARCHAR(2)   NOT NULL,
    reference_tier            VARCHAR(16)  NOT NULL,
    reference_scenario_index  INT          NOT NULL,
    reference_scenario_name   VARCHAR(120) NOT NULL,
    snapshot_date             DATE,
    coverage_percent          DOUBLE PRECISION,
    estimated_year            INT,
    probability               DOUBLE PRECISION,
    payload_json              JSONB        NOT NULL,
    result_json               JSONB,
    observations_count        INT,
    data_gaps_count           INT,
    citations_total           INT,
    citations_ungrounded      INT,
    input_tokens              INT,
    output_tokens             INT,
    reasoning_tokens          INT,
    latency_ms                BIGINT,
    error_code                VARCHAR(64),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at               TIMESTAMPTZ
);

CREATE INDEX idx_fire_ai_analysis_runs_household ON fire_ai_analysis_runs (household_id, created_at DESC);
-- At most one analysis in progress per household at a time.
CREATE UNIQUE INDEX idx_fire_ai_analysis_runs_one_running ON fire_ai_analysis_runs (household_id) WHERE status = 'running';

CREATE TABLE fire_ai_analysis_followups (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id               UUID        NOT NULL REFERENCES fire_ai_analysis_runs(id) ON DELETE CASCADE,
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

CREATE INDEX idx_fire_ai_analysis_followups_run ON fire_ai_analysis_followups (run_id, created_at ASC);
