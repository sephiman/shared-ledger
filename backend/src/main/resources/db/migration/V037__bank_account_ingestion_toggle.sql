-- Per-account pause inside a connection: one consent can grant several accounts, and the holder may want
-- movements from only some of them. Additive; every existing account stays active.
ALTER TABLE bank_connection_accounts ADD COLUMN ingestion_enabled BOOLEAN NOT NULL DEFAULT true;
