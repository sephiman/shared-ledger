-- Allow 12-month savings-only reviews when the selected month is empty or stale.
ALTER TABLE analytics_ai_review_runs
    DROP CONSTRAINT analytics_ai_review_runs_review_kind_check;

ALTER TABLE analytics_ai_review_runs
    ADD CONSTRAINT analytics_ai_review_runs_review_kind_check
    CHECK (review_kind IN ('complete_month', 'pace_review', 'savings_only'));
