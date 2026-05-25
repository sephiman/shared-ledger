ALTER TABLE categories
    ADD COLUMN essential BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE categories
SET essential = TRUE
WHERE code IN (
    'home.rent',
    'home.mortgage',
    'home.utilities',
    'home.insurance_fees',
    'home.taxes',
    'home.repairs',
    'transport.fuel',
    'transport.public',
    'transport.car_maintenance',
    'groceries.groceries',
    'financial.fees',
    'health.medical',
    'health.pharmacy',
    'personal.personal_care'
);
