-- Enable Banking mints a new account uid per session, so every re-link used to add a copy of each
-- account. The identification hash is the provider's stable identity; new links match on it.
ALTER TABLE bank_connection_accounts ADD COLUMN identification_hash VARCHAR(128);

-- Fold existing copies into the oldest row (keeping its id and pause state), which takes the newest
-- uid — the only one the current session still serves. Rows without a masked IBAN are left alone.
CREATE TEMP TABLE bank_account_merge AS
SELECT id,
       first_value(id) OVER (PARTITION BY connection_id, iban_masked, currency, name
                             ORDER BY created_at, id) AS keeper_id,
       first_value(account_uid) OVER (PARTITION BY connection_id, iban_masked, currency, name
                                      ORDER BY created_at DESC, id DESC) AS newest_uid
FROM bank_connection_accounts
WHERE iban_masked IS NOT NULL;

UPDATE pending_movements p
SET account_id = m.keeper_id
FROM bank_account_merge m
WHERE p.account_id = m.id AND m.id <> m.keeper_id;

DELETE FROM bank_connection_accounts a
USING bank_account_merge m
WHERE a.id = m.id AND m.id <> m.keeper_id;

UPDATE bank_connection_accounts a
SET account_uid = m.newest_uid
FROM bank_account_merge m
WHERE a.id = m.id AND m.id = m.keeper_id;

DROP TABLE bank_account_merge;
