-- =============================================================
-- Migration 003: Unified schema
-- Merges Health Connect (live) + Apple Health (historical) data
-- Adds standardised source column to all tables
-- Drops redundant old tables
-- =============================================================

-- Source normalisation used throughout:
--   HC source_package:  com.whoop.android       → whoop
--                       com.oceanwing.smarthome → smart_scale
--                       com.sbs.diet            → diet_app
--   AH source_name:     WHOOP                   → whoop
--                       COROS                   → coros
--                       Alan's Apple Watch      → apple_watch
--                       Alan's iPhone / iPhone / user's iPhone → iphone
--                       MacroFactor             → macrofactor
--                       Health                  → health_app
--                       Oura                    → oura
--                       Ultrahuman              → ultrahuman
--                       AutoSleep               → autosleep
--                       Strava                  → strava
--                       Boditrax 2.0            → boditrax
--                       YUNMAI                  → yunmai
--                       MyFitnessPal            → myfitnesspal
--                       eufy Life               → eufy
--                       Zero                    → zero

-- ──────────────────────────────────────────────────────────────
-- 1. weight_logs  (replaces weight_records + AH BodyMass)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS weight_logs (
    id           UUID          PRIMARY KEY,
    recorded_at  TIMESTAMPTZ   NOT NULL,
    weight_kg    NUMERIC(6,2)  NOT NULL,
    source       TEXT          NOT NULL,
    raw          JSONB,
    inserted_at  TIMESTAMPTZ   DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wl_source_time ON weight_logs (source, recorded_at);
CREATE        INDEX IF NOT EXISTS idx_wl_recorded_at ON weight_logs (recorded_at);

-- From Health Connect (smart scale, live)
INSERT INTO weight_logs (id, recorded_at, weight_kg, source, raw)
SELECT id, recorded_time, weight_kg,
    CASE source_package
        WHEN 'com.oceanwing.smarthome' THEN 'smart_scale'
        WHEN 'com.whoop.android'       THEN 'whoop'
        ELSE source_package
    END, raw
FROM weight_records
ON CONFLICT (source, recorded_at) DO NOTHING;

-- From Apple Health historical (BodyMass records)
INSERT INTO weight_logs (id, recorded_at, weight_kg, source, raw)
SELECT id, start_time, value::NUMERIC(6,2),
    CASE source_name
        WHEN 'WHOOP'               THEN 'whoop'
        WHEN 'COROS'               THEN 'coros'
        WHEN 'Health'              THEN 'health_app'
        WHEN 'Alan''s Apple Watch' THEN 'apple_watch'
        WHEN 'Alan''s iPhone'      THEN 'iphone'
        WHEN 'iPhone'              THEN 'iphone'
        WHEN 'user''s iPhone'      THEN 'iphone'
        WHEN 'Oura'                THEN 'oura'
        WHEN 'Ultrahuman'          THEN 'ultrahuman'
        WHEN 'Boditrax 2.0'        THEN 'boditrax'
        WHEN 'YUNMAI'              THEN 'yunmai'
        WHEN 'eufy Life'           THEN 'eufy'
        ELSE lower(replace(source_name, ' ', '_'))
    END, NULL
FROM apple_health_quantity
WHERE type = 'HKQuantityTypeIdentifierBodyMass'
ON CONFLICT (source, recorded_at) DO NOTHING;

-- ──────────────────────────────────────────────────────────────
-- 2. workouts  (replaces exercise_records + apple_health_workouts)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workouts (
    id                 UUID          PRIMARY KEY,
    activity_type      TEXT          NOT NULL,
    start_time         TIMESTAMPTZ   NOT NULL,
    end_time           TIMESTAMPTZ   NOT NULL,
    duration_min       NUMERIC(8,3),
    energy_kcal        NUMERIC(10,3),
    distance_km        NUMERIC(10,6),
    hr_avg             NUMERIC(6,2),
    hr_min             NUMERIC(6,2),
    hr_max             NUMERIC(6,2),
    source             TEXT          NOT NULL,
    metadata           JSONB,
    raw                JSONB,
    inserted_at        TIMESTAMPTZ   DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wo_source_start  ON workouts (source, start_time);
CREATE        INDEX IF NOT EXISTS idx_wo_start_time    ON workouts (start_time);
CREATE        INDEX IF NOT EXISTS idx_wo_activity      ON workouts (activity_type, start_time);

-- From Apple Health historical (richer data, insert first)
INSERT INTO workouts
    (id, activity_type, start_time, end_time, duration_min, energy_kcal,
     distance_km, hr_avg, hr_min, hr_max, source, metadata, raw)
SELECT
    id,
    replace(activity_type, 'HKWorkoutActivityType', ''),
    start_time, end_time, duration_min,
    COALESCE(total_energy_kcal, active_energy_kcal),
    total_distance_km, hr_avg, hr_min, hr_max,
    CASE source_name
        WHEN 'WHOOP'               THEN 'whoop'
        WHEN 'COROS'               THEN 'coros'
        WHEN 'Oura'                THEN 'oura'
        WHEN 'Strava'              THEN 'strava'
        WHEN 'Ultrahuman'          THEN 'ultrahuman'
        WHEN 'Alan''s Apple Watch' THEN 'apple_watch'
        WHEN 'Alan''s iPhone'      THEN 'iphone'
        WHEN 'Health'              THEN 'health_app'
        ELSE lower(replace(source_name, ' ', '_'))
    END,
    metadata, NULL
FROM apple_health_workouts
ON CONFLICT (source, start_time) DO NOTHING;

-- From Health Connect live (skip duplicates already covered by AH)
INSERT INTO workouts
    (id, activity_type, start_time, end_time, duration_min, source, raw)
SELECT
    id,
    CASE exercise_type_code
        WHEN  2 THEN 'Badminton'           WHEN  4 THEN 'Baseball'
        WHEN  5 THEN 'Basketball'          WHEN  8 THEN 'Biking'
        WHEN  9 THEN 'Biking Stationary'   WHEN 10 THEN 'Boot Camp'
        WHEN 11 THEN 'Boxing'              WHEN 13 THEN 'Calisthenics'
        WHEN 14 THEN 'Cricket'             WHEN 16 THEN 'Dancing'
        WHEN 21 THEN 'Elliptical'          WHEN 22 THEN 'Exercise Class'
        WHEN 25 THEN 'Football American'   WHEN 26 THEN 'Frisbee'
        WHEN 27 THEN 'Golf'                WHEN 28 THEN 'Guided Breathing'
        WHEN 29 THEN 'Gymnastics'          WHEN 31 THEN 'HIIT'
        WHEN 32 THEN 'Hiking'              WHEN 36 THEN 'Martial Arts'
        WHEN 37 THEN 'Paddling'            WHEN 39 THEN 'Pilates'
        WHEN 43 THEN 'Rowing'              WHEN 44 THEN 'Rowing Machine'
        WHEN 45 THEN 'Rugby'               WHEN 56 THEN 'Running'
        WHEN 57 THEN 'Running Treadmill'   WHEN 61 THEN 'Skiing'
        WHEN 62 THEN 'Snowboarding'        WHEN 64 THEN 'Soccer'
        WHEN 66 THEN 'Squash'              WHEN 67 THEN 'Stair Climbing'
        WHEN 69 THEN 'Strength Training'   WHEN 70 THEN 'Stretching'
        WHEN 71 THEN 'Surfing'             WHEN 72 THEN 'Swimming Open Water'
        WHEN 73 THEN 'Swimming Pool'       WHEN 74 THEN 'Table Tennis'
        WHEN 75 THEN 'Tennis'              WHEN 76 THEN 'Volleyball'
        WHEN 79 THEN 'Walking'             WHEN 80 THEN 'Water Polo'
        WHEN 81 THEN 'Weightlifting'       WHEN 82 THEN 'Wheelchair'
        WHEN 83 THEN 'Yoga'
        ELSE 'Type ' || exercise_type_code::text
    END,
    start_time, end_time,
    ROUND(EXTRACT(EPOCH FROM (end_time - start_time)) / 60.0, 3),
    CASE source_package
        WHEN 'com.whoop.android' THEN 'whoop'
        ELSE source_package
    END,
    raw
FROM exercise_records
ON CONFLICT (source, start_time) DO NOTHING;

-- ──────────────────────────────────────────────────────────────
-- 3. meal_logs  (replaces nutrition_records + apple_health_nutrition)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS meal_logs (
    id                    UUID          PRIMARY KEY,
    logged_at             TIMESTAMPTZ   NOT NULL,
    end_time              TIMESTAMPTZ,
    name                  TEXT,
    energy_kcal           NUMERIC(10,3),
    protein_g             NUMERIC(10,4),
    carbs_g               NUMERIC(10,4),
    fat_g                 NUMERIC(10,4),
    fat_saturated_g       NUMERIC(10,4),
    fat_polyunsaturated_g NUMERIC(10,4),
    fat_monounsaturated_g NUMERIC(10,4),
    fiber_g               NUMERIC(10,4),
    sugar_g               NUMERIC(10,4),
    cholesterol_mg        NUMERIC(10,4),
    sodium_mg             NUMERIC(10,4),
    potassium_mg          NUMERIC(10,4),
    calcium_mg            NUMERIC(10,4),
    iron_mg               NUMERIC(10,4),
    magnesium_mg          NUMERIC(10,4),
    phosphorus_mg         NUMERIC(10,4),
    zinc_mg               NUMERIC(10,4),
    copper_mg             NUMERIC(10,4),
    manganese_mg          NUMERIC(10,4),
    selenium_mcg          NUMERIC(10,4),
    vitamin_a_mcg         NUMERIC(10,4),
    vitamin_b6_mg         NUMERIC(10,4),
    vitamin_b12_mcg       NUMERIC(10,4),
    vitamin_c_mg          NUMERIC(10,4),
    vitamin_d_mcg         NUMERIC(10,4),
    vitamin_e_mg          NUMERIC(10,4),
    vitamin_k_mcg         NUMERIC(10,4),
    riboflavin_mg         NUMERIC(10,4),
    niacin_mg             NUMERIC(10,4),
    thiamin_mg            NUMERIC(10,4),
    pantothenic_acid_mg   NUMERIC(10,4),
    folate_mcg            NUMERIC(10,4),
    caffeine_mg           NUMERIC(10,4),
    water_ml              NUMERIC(10,4),
    source                TEXT          NOT NULL,
    raw                   JSONB,
    inserted_at           TIMESTAMPTZ   DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ml_source_time ON meal_logs (source, logged_at);
CREATE        INDEX IF NOT EXISTS idx_ml_logged_at   ON meal_logs (logged_at);

-- From Apple Health nutrition (MacroFactor historical, full micronutrients)
INSERT INTO meal_logs
    (id, logged_at, end_time, energy_kcal, protein_g, carbs_g, fat_g,
     fat_saturated_g, fat_polyunsaturated_g, fat_monounsaturated_g, fiber_g, sugar_g,
     cholesterol_mg, sodium_mg, potassium_mg, calcium_mg, iron_mg, magnesium_mg,
     phosphorus_mg, zinc_mg, copper_mg, manganese_mg, selenium_mcg,
     vitamin_a_mcg, vitamin_b6_mg, vitamin_b12_mcg, vitamin_c_mg, vitamin_d_mcg,
     vitamin_e_mg, vitamin_k_mcg, riboflavin_mg, niacin_mg, thiamin_mg,
     pantothenic_acid_mg, folate_mcg, caffeine_mg, water_ml, source, raw)
SELECT
    id, start_time, end_time, energy_kcal, protein_g, carbs_g, fat_total_g,
    fat_saturated_g, fat_polyunsaturated_g, fat_monounsaturated_g, fiber_g, sugar_g,
    cholesterol_mg, sodium_mg, potassium_mg, calcium_mg, iron_mg, magnesium_mg,
    phosphorus_mg, zinc_mg, copper_mg, manganese_mg, selenium_mcg,
    vitamin_a_mcg, vitamin_b6_mg, vitamin_b12_mcg, vitamin_c_mg, vitamin_d_mcg,
    vitamin_e_mg, vitamin_k_mcg, riboflavin_mg, niacin_mg, thiamin_mg,
    pantothenic_acid_mg, folate_mcg, caffeine_mg, water_ml,
    CASE source_name
        WHEN 'MacroFactor'   THEN 'macrofactor'
        WHEN 'MyFitnessPal'  THEN 'myfitnesspal'
        ELSE lower(replace(source_name, ' ', '_'))
    END, NULL
FROM apple_health_nutrition
ON CONFLICT (source, logged_at) DO NOTHING;

-- From Health Connect live (diet app)
INSERT INTO meal_logs
    (id, logged_at, end_time, name, energy_kcal, protein_g, carbs_g, fat_g, source, raw)
SELECT
    id, start_time, end_time, name, energy_kcal, protein_g, carbs_g, fat_g,
    CASE source_package
        WHEN 'com.sbs.diet'      THEN 'diet_app'
        WHEN 'com.whoop.android' THEN 'whoop'
        ELSE source_package
    END, raw
FROM nutrition_records
ON CONFLICT (source, logged_at) DO NOTHING;

-- ──────────────────────────────────────────────────────────────
-- 4. apple_health_quantity → biometric_timeseries  + source column
-- ──────────────────────────────────────────────────────────────
ALTER TABLE apple_health_quantity ADD COLUMN IF NOT EXISTS source TEXT;

UPDATE apple_health_quantity SET source = CASE source_name
    WHEN 'WHOOP'               THEN 'whoop'
    WHEN 'COROS'               THEN 'coros'
    WHEN 'Oura'                THEN 'oura'
    WHEN 'Strava'              THEN 'strava'
    WHEN 'Ultrahuman'          THEN 'ultrahuman'
    WHEN 'MacroFactor'         THEN 'macrofactor'
    WHEN 'MyFitnessPal'        THEN 'myfitnesspal'
    WHEN 'Health'              THEN 'health_app'
    WHEN 'Alan''s Apple Watch' THEN 'apple_watch'
    WHEN 'Alan''s iPhone'      THEN 'iphone'
    WHEN 'Alan''s iPad'        THEN 'ipad'
    WHEN 'iPhone'              THEN 'iphone'
    WHEN 'user''s iPhone'      THEN 'iphone'
    WHEN 'Blood Oxygen'        THEN 'blood_oxygen'
    WHEN 'Bluetooth Device'    THEN 'bluetooth_device'
    WHEN 'Boditrax 2.0'        THEN 'boditrax'
    WHEN 'YUNMAI'              THEN 'yunmai'
    WHEN 'eufy Life'           THEN 'eufy'
    WHEN 'Zero'                THEN 'zero'
    WHEN 'AutoSleep'           THEN 'autosleep'
    ELSE lower(replace(source_name, ' ', '_'))
END
WHERE source IS NULL;

-- Remove BodyMass rows now unified in weight_logs
DELETE FROM apple_health_quantity WHERE type = 'HKQuantityTypeIdentifierBodyMass';

ALTER TABLE apple_health_quantity RENAME TO biometric_timeseries;

CREATE INDEX IF NOT EXISTS idx_bts_source ON biometric_timeseries (source, type, start_time);

-- ──────────────────────────────────────────────────────────────
-- 5. apple_health_sleep → sleep_records  + source column
-- ──────────────────────────────────────────────────────────────
ALTER TABLE apple_health_sleep ADD COLUMN IF NOT EXISTS source TEXT;

UPDATE apple_health_sleep SET source = CASE source_name
    WHEN 'WHOOP'               THEN 'whoop'
    WHEN 'COROS'               THEN 'coros'
    WHEN 'Oura'                THEN 'oura'
    WHEN 'Ultrahuman'          THEN 'ultrahuman'
    WHEN 'AutoSleep'           THEN 'autosleep'
    WHEN 'Alan''s Apple Watch' THEN 'apple_watch'
    WHEN 'Alan''s iPhone'      THEN 'iphone'
    WHEN 'Health'              THEN 'health_app'
    ELSE lower(replace(source_name, ' ', '_'))
END
WHERE source IS NULL;

ALTER TABLE apple_health_sleep RENAME TO sleep_records;

CREATE INDEX IF NOT EXISTS idx_sr_source ON sleep_records (source, stage, start_time);

-- ──────────────────────────────────────────────────────────────
-- 6. apple_health_ecg → ecg_records
-- ──────────────────────────────────────────────────────────────
ALTER TABLE apple_health_ecg RENAME TO ecg_records;

-- ──────────────────────────────────────────────────────────────
-- 7. Drop old tables (data already migrated above)
-- ──────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS weight_records;
DROP TABLE IF EXISTS exercise_records;
DROP TABLE IF EXISTS nutrition_records;
DROP TABLE IF EXISTS apple_health_workouts;
DROP TABLE IF EXISTS apple_health_nutrition;
