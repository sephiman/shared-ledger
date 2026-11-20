-- Per-household decision memory for Spending review findings (keep, compared, not_recurring, split).
CREATE TABLE spending_review_decisions (
    id                  UUID PRIMARY KEY,
    household_id        UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    subject_key         VARCHAR(255) NOT NULL,
    subject_kind        VARCHAR(32)  NOT NULL,
    category_code       VARCHAR(64)  NOT NULL,
    decision_type       VARCHAR(32)  NOT NULL,
    display_label       VARCHAR(200) NOT NULL,
    member_keys_json    TEXT         NOT NULL DEFAULT '[]',
    split_groups_json   TEXT,
    amount_at_decision  NUMERIC(15,2),
    snooze_until        DATE,
    decided_by_user_id  UUID         NOT NULL REFERENCES users(id),
    decided_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_spending_review_decisions_household_subject UNIQUE (household_id, subject_key)
);

CREATE INDEX idx_spending_review_decisions_household ON spending_review_decisions(household_id);
