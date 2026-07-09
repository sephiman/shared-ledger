-- Rename the "loans" feature (money the household lends to others) to "lendings" throughout.
-- Pure rename: no data or structural change, just table/column/index/toggle names.

-- loans -> lendings
ALTER TABLE loans RENAME TO lendings;
ALTER INDEX idx_loans_household_active RENAME TO idx_lendings_household_active;
ALTER INDEX idx_loans_household RENAME TO idx_lendings_household;

-- loan_schedules -> lending_schedules
ALTER TABLE loan_schedules RENAME COLUMN loan_id TO lending_id;
ALTER TABLE loan_schedules RENAME TO lending_schedules;
ALTER INDEX idx_loan_schedules_loan RENAME TO idx_lending_schedules_lending;
ALTER INDEX idx_loan_schedules_active RENAME TO idx_lending_schedules_active;

-- loan_payments -> lending_payments
ALTER TABLE loan_payments RENAME COLUMN loan_id TO lending_id;
ALTER TABLE loan_payments RENAME TO lending_payments;
ALTER INDEX idx_loan_payments_loan_date RENAME TO idx_lending_payments_lending_date;
ALTER INDEX idx_loan_payments_schedule_date_unique RENAME TO idx_lending_payments_schedule_date_unique;

-- Telegram notification toggles for the lending feature
ALTER TABLE telegram_settings RENAME COLUMN notify_loan_payments TO notify_lending_payments;
ALTER TABLE telegram_settings RENAME COLUMN notify_recurring_loan TO notify_recurring_lending;
