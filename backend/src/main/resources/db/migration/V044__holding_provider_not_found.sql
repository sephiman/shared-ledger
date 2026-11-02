-- Day the price provider first answered 404 for the holding's provider symbol (delisted, renamed or mistyped).
-- Null while the symbol resolves; cleared on the next successful fetch or on relink/unlink.
ALTER TABLE holdings ADD COLUMN provider_not_found_since DATE;
