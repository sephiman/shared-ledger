-- When a household's banks sync in the background: one or two wall-clock times in the household's zone.
-- No row = the instance default (07:00 and 19:00 UTC).
CREATE TABLE bank_sync_schedules (
    household_id        UUID PRIMARY KEY REFERENCES households(id) ON DELETE CASCADE,
    first_time          TIME NOT NULL,
    second_time         TIME,
    timezone            VARCHAR(64) NOT NULL,
    updated_by_user_id  UUID REFERENCES users(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (second_time IS NULL OR second_time <> first_time)
);
