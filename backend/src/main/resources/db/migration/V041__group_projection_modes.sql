-- How the month projection estimates each expense group's discretionary spend. Sparse: a group with no row
-- uses ENVELOPE (the behaviour before this table existed), so adding it changes no projected number.
CREATE TABLE group_projection_modes (
    household_id UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    group_code   VARCHAR(32) NOT NULL,
    mode         VARCHAR(16) NOT NULL CHECK (mode IN ('ENVELOPE', 'PRORATE')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, group_code)
);
