ALTER TABLE users
    ADD COLUMN default_household_id UUID REFERENCES households(id) ON DELETE SET NULL;
