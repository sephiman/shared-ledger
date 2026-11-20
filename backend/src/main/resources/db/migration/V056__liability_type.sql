-- Liability type ('mortgage' | 'other_debt'); validated in the service layer, not by a CHECK constraint.
ALTER TABLE liabilities
    ADD COLUMN type VARCHAR(24) NOT NULL DEFAULT 'other_debt';
