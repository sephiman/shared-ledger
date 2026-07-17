-- FIRE spending bases become overridable: each base (essential, total) gets a persisted
-- source mode — 'derived' (live trailing-12, the previous and default behavior) or 'manual'
-- (a user-entered monthly amount). Additive only; existing households keep 'derived'.

ALTER TABLE fire_settings
    ADD COLUMN essential_spending_mode   VARCHAR(16)   NOT NULL DEFAULT 'derived',
    ADD COLUMN manual_essential_spending NUMERIC(15,2) NOT NULL DEFAULT 0,
    ADD COLUMN total_spending_mode       VARCHAR(16)   NOT NULL DEFAULT 'derived',
    ADD COLUMN manual_total_spending     NUMERIC(15,2) NOT NULL DEFAULT 0;
