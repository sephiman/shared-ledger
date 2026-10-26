-- AI category suggestions for the pending inbox. A suggestion now records where it came from: a rule (sync or
-- Apply rules) or the AI button, with the model's confidence for the latter. Additive: no existing value changes.
ALTER TABLE pending_movements
    ADD COLUMN suggestion_source     VARCHAR(8),
    ADD COLUMN suggestion_confidence NUMERIC(3, 2);

-- Until now only rules wrote a suggestion onto a still-open item (the PATCH endpoint has no UI caller). Confirmed
-- items keep NULL: their code is the category the member chose, not a suggestion.
UPDATE pending_movements
SET suggestion_source = 'rule'
WHERE status IN ('pending', 'rejected')
  AND suggested_category_code IS NOT NULL
  AND suggested_category_code <> '';

-- Per-household opt-in, off by default (no row = off).
CREATE TABLE ai_suggestion_settings (
    household_id       UUID PRIMARY KEY REFERENCES households(id) ON DELETE CASCADE,
    enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per press of "Suggest with AI": progress for the inbox and the run record. Token figures are kept
-- for the operator and never returned by the API.
CREATE TABLE ai_suggestion_runs (
    id                 UUID PRIMARY KEY,
    household_id       UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id            UUID        REFERENCES users(id) ON DELETE SET NULL,
    status             VARCHAR(16) NOT NULL,
    prompt_version     VARCHAR(16) NOT NULL,
    model              VARCHAR(128) NOT NULL,
    batches_total      INT         NOT NULL,
    batches_done       INT         NOT NULL DEFAULT 0,
    batches_failed     INT         NOT NULL DEFAULT 0,
    items_sent         INT         NOT NULL DEFAULT 0,
    suggestions_stored INT         NOT NULL DEFAULT 0,
    items_unsuggested  INT         NOT NULL DEFAULT 0,
    input_tokens       BIGINT      NOT NULL DEFAULT 0,
    output_tokens      BIGINT      NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ
);

CREATE INDEX ai_suggestion_runs_household_started ON ai_suggestion_runs (household_id, started_at DESC);
-- At most one run in progress per household: a second press fails here, whatever the timing.
CREATE UNIQUE INDEX ai_suggestion_runs_one_running ON ai_suggestion_runs (household_id) WHERE status = 'running';
