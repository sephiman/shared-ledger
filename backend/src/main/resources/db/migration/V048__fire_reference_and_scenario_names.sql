-- Add household-level reference plan (tier + scenario) and editable scenario names.
-- Strictly additive; no existing column is dropped or reinterpreted.

ALTER TABLE fire_settings
    ADD COLUMN reference_tier VARCHAR(16) NOT NULL DEFAULT 'fire',
    ADD COLUMN reference_scenario_index SMALLINT NOT NULL DEFAULT 1;

WITH ranked AS (
    SELECT
        fs.household_id,
        h.default_locale,
        elem_with_ord.ord,
        elem_with_ord.elem,
        ROW_NUMBER() OVER (
            PARTITION BY fs.household_id
            ORDER BY (elem_with_ord.elem->>'meanPercent')::numeric ASC, elem_with_ord.ord ASC
        ) AS rk,
        COUNT(*) OVER (PARTITION BY fs.household_id) AS cnt
    FROM fire_settings fs
    JOIN households h ON h.id = fs.household_id
    CROSS JOIN LATERAL jsonb_array_elements(fs.return_scenarios) WITH ORDINALITY AS elem_with_ord(elem, ord)
),
named AS (
    SELECT
        household_id,
        ord,
        rk,
        cnt,
        (elem->>'meanPercent')::numeric AS mean_val,
        CASE
            WHEN NULLIF(BTRIM(elem->>'name'), '') IS NOT NULL THEN elem
            ELSE jsonb_set(
                elem,
                '{name}',
                to_jsonb(
                    CASE
                        WHEN default_locale = 'es' THEN
                            CASE rk
                                WHEN 1 THEN 'Conservador'
                                WHEN 2 THEN 'Base'
                                WHEN 3 THEN 'Optimista'
                                ELSE 'Escenario ' || rk::text
                            END
                        ELSE
                            CASE rk
                                WHEN 1 THEN 'Conservative'
                                WHEN 2 THEN 'Base'
                                WHEN 3 THEN 'Optimistic'
                                ELSE 'Scenario ' || rk::text
                            END
                    END
                )
            )
        END AS updated_elem
    FROM ranked
),
agg AS (
    SELECT
        household_id,
        jsonb_agg(updated_elem ORDER BY ord ASC) AS scenarios_json,
        MAX(CASE WHEN rk = (cnt / 2) + 1 THEN mean_val END) AS mid_mean
    FROM named
    GROUP BY household_id
),
ref_idx AS (
    SELECT
        n.household_id,
        (MIN(n.ord) - 1)::smallint AS ref_index
    FROM named n
    JOIN agg a ON a.household_id = n.household_id
    WHERE n.mean_val = a.mid_mean
    GROUP BY n.household_id
)
UPDATE fire_settings fs
SET
    return_scenarios = agg.scenarios_json,
    reference_scenario_index = COALESCE(ref_idx.ref_index, 0)
FROM agg
LEFT JOIN ref_idx ON ref_idx.household_id = agg.household_id
WHERE fs.household_id = agg.household_id;

ALTER TABLE fire_settings
    ALTER COLUMN return_scenarios SET DEFAULT '[
        {"name": "Conservative", "meanPercent": 4.0, "stdDevPercent": 5.0},
        {"name": "Base", "meanPercent": 6.0, "stdDevPercent": 12.0},
        {"name": "Optimistic", "meanPercent": 8.0, "stdDevPercent": 18.0}
    ]'::jsonb;
