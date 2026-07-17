-- FIRE v2: nominal framework, Lean/FIRE/Fat tiers, contribution modes, capital-gains tax.
-- Strictly additive: no column is dropped or reinterpreted. The pre-existing target_amount
-- keeps its meaning and becomes the optional "custom" tier; households that had a target
-- configured keep it active so nobody loses their configuration.

ALTER TABLE fire_settings
    ADD COLUMN expected_inflation_pct     NUMERIC(5,2) NOT NULL DEFAULT 2.0,
    ADD COLUMN safe_withdrawal_rate_pct   NUMERIC(5,2) NOT NULL DEFAULT 4.0,
    ADD COLUMN fat_multiplier             NUMERIC(5,2) NOT NULL DEFAULT 1.5,
    ADD COLUMN contribution_mode          VARCHAR(16)  NOT NULL DEFAULT 'manual',
    ADD COLUMN index_contribution         BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN tier_lean_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN tier_fire_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN tier_fat_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN tier_custom_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN apply_capital_gains_tax    BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN fallback_gain_fraction_pct NUMERIC(5,2) NOT NULL DEFAULT 50.0;

UPDATE fire_settings SET tier_custom_enabled = TRUE WHERE target_amount > 0;

-- Capital-gains brackets are data, not code: each household owns an editable copy,
-- seeded with the Spanish savings-base scale in force (Ley 7/2024, fiscal years 2025-2026).
-- The upper bound of a bracket is implicit: the next bracket's lower bound.
CREATE TABLE fire_tax_brackets (
    household_id UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    lower_bound  NUMERIC(15,2) NOT NULL,
    rate_pct     NUMERIC(5,2)  NOT NULL,
    PRIMARY KEY (household_id, lower_bound)
);

INSERT INTO fire_tax_brackets (household_id, lower_bound, rate_pct)
SELECT h.id, b.lower_bound, b.rate_pct
FROM households h
CROSS JOIN (VALUES
    (0.00,      19.0),
    (6000.00,   21.0),
    (50000.00,  23.0),
    (200000.00, 27.0),
    (300000.00, 30.0)
) AS b(lower_bound, rate_pct);
