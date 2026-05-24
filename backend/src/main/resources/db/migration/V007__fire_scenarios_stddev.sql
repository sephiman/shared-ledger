-- Convert return_scenarios from "array of numbers" to "array of {meanPercent, stdDevPercent}".
-- Any existing scalar entry gets paired with a 12% stddev (balanced-portfolio default);
-- entries that are already objects are preserved.
UPDATE fire_settings
SET return_scenarios = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(elem) = 'number' THEN
                jsonb_build_object('meanPercent', elem, 'stdDevPercent', 12.0)
            ELSE elem
        END
    )
    FROM jsonb_array_elements(return_scenarios) AS elem
)
WHERE jsonb_typeof(return_scenarios) = 'array'
  AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(return_scenarios) e
      WHERE jsonb_typeof(e) = 'number'
  );

ALTER TABLE fire_settings
    ALTER COLUMN return_scenarios SET DEFAULT '[
        {"meanPercent": 4.0, "stdDevPercent": 5.0},
        {"meanPercent": 6.0, "stdDevPercent": 12.0},
        {"meanPercent": 8.0, "stdDevPercent": 18.0}
    ]'::jsonb;
