-- Real identification hashes exceed 128 chars (V039 guessed wrong); the provider documents no bound.
ALTER TABLE bank_connection_accounts ALTER COLUMN identification_hash TYPE TEXT;
