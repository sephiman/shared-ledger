CREATE TABLE categories (
    code        VARCHAR(64) PRIMARY KEY,
    kind        VARCHAR(16) NOT NULL CHECK (kind IN ('income','expense')),
    group_code  VARCHAR(32),
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE asset_classes (
    code        VARCHAR(32) PRIMARY KEY,
    sort_order  INT NOT NULL DEFAULT 0
);

-- Income categories (flat, no group)
INSERT INTO categories (code, kind, group_code, sort_order) VALUES
    ('income.salary',            'income', NULL, 10),
    ('income.pension',           'income', NULL, 20),
    ('income.reimbursements',    'income', NULL, 30),
    ('income.benefits',          'income', NULL, 40),
    ('income.financial',         'income', NULL, 50),
    ('income.other',             'income', NULL, 60),
    ('income.transfers',         'income', NULL, 70);

-- Expense categories
INSERT INTO categories (code, kind, group_code, sort_order) VALUES
    ('home.rent',                 'expense', 'home', 10),
    ('home.mortgage',             'expense', 'home', 20),
    ('home.utilities',            'expense', 'home', 30),
    ('home.insurance_fees',       'expense', 'home', 40),
    ('home.services',             'expense', 'home', 50),
    ('home.taxes',                'expense', 'home', 60),
    ('home.repairs',              'expense', 'home', 70),
    ('home.furniture',            'expense', 'home', 80),
    ('home.other',                'expense', 'home', 90),

    ('transport.fuel',            'expense', 'transport', 10),
    ('transport.public',          'expense', 'transport', 20),
    ('transport.airfare',         'expense', 'transport', 30),
    ('transport.car_maintenance', 'expense', 'transport', 40),
    ('transport.parking',         'expense', 'transport', 60),
    ('transport.other',           'expense', 'transport', 80),

    ('groceries.groceries',       'expense', 'groceries', 10),

    ('shopping.clothing',         'expense', 'shopping', 10),
    ('shopping.electronics',      'expense', 'shopping', 20),
    ('shopping.gifts',            'expense', 'shopping', 30),
    ('shopping.other',            'expense', 'shopping', 50),

    ('outings.restaurants',       'expense', 'outings', 10),
    ('outings.travel',            'expense', 'outings', 40),
    ('outings.subscriptions',     'expense', 'outings', 50),
    ('outings.hobbies',           'expense', 'outings', 60),
    ('outings.lottery',           'expense', 'outings', 70),

    ('financial.fees',            'expense', 'financial', 10),

    ('health.medical',            'expense', 'health', 10),
    ('health.pharmacy',           'expense', 'health', 20),

    ('personal.personal_care',    'expense', 'personal', 10),
    ('personal.education',        'expense', 'personal', 20),
    ('personal.other',            'expense', 'personal', 30);

INSERT INTO asset_classes (code, sort_order) VALUES
    ('cash',         10),
    ('index_funds',  20),
    ('etfs',         30),
    ('stocks',       40),
    ('crypto',       50),
    ('pension',      60);
