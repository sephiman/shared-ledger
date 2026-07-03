-- Rename the 'index_funds' asset class code to 'fund'.
-- asset_classes.code is referenced by snapshot_asset_values and net_worth_movements
-- (NO ACTION FKs), so insert the new code, repoint referencing rows, then drop the old one.
INSERT INTO asset_classes (code, sort_order) VALUES ('fund', 20);

UPDATE snapshot_asset_values SET asset_class_code = 'fund' WHERE asset_class_code = 'index_funds';
UPDATE net_worth_movements SET asset_class_code = 'fund' WHERE asset_class_code = 'index_funds';

UPDATE fire_settings
SET qualifying_asset_classes = array_replace(qualifying_asset_classes, 'index_funds', 'fund');
ALTER TABLE fire_settings
    ALTER COLUMN qualifying_asset_classes SET DEFAULT ARRAY['fund','etfs','stocks','crypto','pension'];

DELETE FROM asset_classes WHERE code = 'index_funds';
