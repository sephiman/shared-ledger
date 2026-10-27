-- One row per press of "Suggest generalised rules": progress for the rules page and the run record. The
-- proposals themselves are never stored; the instance holds them in memory for a short while. Token figures
-- are kept for the operator and never returned by the API.
CREATE TABLE ai_rule_generalisation_runs (
    id                  UUID PRIMARY KEY,
    household_id        UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id             UUID         REFERENCES users(id) ON DELETE SET NULL,
    status              VARCHAR(16)  NOT NULL,
    prompt_version      VARCHAR(16)  NOT NULL,
    model               VARCHAR(128) NOT NULL,
    rules_total         INT          NOT NULL,
    rules_sent          INT          NOT NULL,
    proposals_returned  INT          NOT NULL DEFAULT 0,
    proposals_discarded INT          NOT NULL DEFAULT 0,
    proposals_shown     INT          NOT NULL DEFAULT 0,
    proposals_accepted  INT          NOT NULL DEFAULT 0,
    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ
);

CREATE INDEX ai_rule_generalisation_runs_household_started ON ai_rule_generalisation_runs (household_id, started_at DESC);
-- At most one run in progress per household, as for ai_suggestion_runs.
CREATE UNIQUE INDEX ai_rule_generalisation_runs_one_running ON ai_rule_generalisation_runs (household_id) WHERE status = 'running';
