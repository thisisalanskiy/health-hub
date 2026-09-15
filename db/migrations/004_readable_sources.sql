-- =============================================================
-- Migration 004: Human-readable source names
-- Replaces technical/lowercased identifiers with display names
-- =============================================================

-- Reusable mapping function (dropped at end)
CREATE OR REPLACE FUNCTION _readable_source(s TEXT) RETURNS TEXT AS $$
BEGIN
    RETURN CASE s
        WHEN 'user''s_apple watch' THEN 'Apple Watch'
        WHEN 'apple_watch'         THEN 'Apple Watch'
        WHEN 'user''s_ipad'        THEN 'iPad'
        WHEN 'user''s_iphone'      THEN 'iPhone'
        WHEN 'iphone'              THEN 'iPhone'
        WHEN 'autosleep'           THEN 'AutoSleep'
        WHEN 'blood_oxygen'        THEN 'Blood Oxygen'
        WHEN 'bluetooth_device'    THEN 'Bluetooth'
        WHEN 'boditrax'            THEN 'Boditrax'
        WHEN 'coros'               THEN 'COROS'
        WHEN 'com.garmin.android.apps.connectmobile' THEN 'Garmin'
        WHEN 'diet_app'            THEN 'Diet App'
        WHEN 'eufy'                THEN 'Eufy'
        WHEN 'fi.polar.polarflow'  THEN 'Polar'
        WHEN 'health_app'          THEN 'Health'
        WHEN 'macrofactor'         THEN 'MacroFactor'
        WHEN 'myfitnesspal'        THEN 'MyFitnessPal'
        WHEN 'oura'                THEN 'Oura'
        WHEN 'smart_scale'         THEN 'Smart Scale'
        WHEN 'strava'              THEN 'Strava'
        WHEN 'ultrahuman'          THEN 'Ultrahuman'
        WHEN 'whoop'               THEN 'WHOOP'
        WHEN 'yunmai'              THEN 'YUNMAI'
        WHEN 'zero'                THEN 'Zero'
        ELSE s
    END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

UPDATE biometric_timeseries SET source = _readable_source(source);
UPDATE sleep_records        SET source = _readable_source(source);
UPDATE weight_logs          SET source = _readable_source(source);
UPDATE workouts             SET source = _readable_source(source);

-- meal_logs: Health Connect system package gets its own label
UPDATE meal_logs SET source = 'Health Connect'
    WHERE source LIKE 'com.android.healthconnect%';
UPDATE meal_logs SET source = _readable_source(source)
    WHERE source NOT IN ('Health Connect', 'MacroFactor', 'Diet App', 'WHOOP');

DROP FUNCTION _readable_source;

-- Fix curly-apostrophe (U+2019) + non-breaking-space (U+00A0) variants
-- that the CASE above couldn't match with straight-quote literals
UPDATE biometric_timeseries SET source = 'Apple Watch' WHERE source LIKE 'user%apple%watch';
UPDATE biometric_timeseries SET source = 'iPad'        WHERE source LIKE 'user%ipad';
UPDATE biometric_timeseries SET source = 'iPhone'      WHERE source LIKE 'user%iphone';
UPDATE sleep_records SET source = 'Apple Watch' WHERE source LIKE 'user%apple%watch';
UPDATE sleep_records SET source = 'iPhone'      WHERE source LIKE 'user%iphone';
UPDATE weight_logs   SET source = 'Apple Watch' WHERE source LIKE 'user%apple%watch';
UPDATE weight_logs   SET source = 'iPhone'      WHERE source LIKE 'user%iphone';
UPDATE workouts      SET source = 'Apple Watch' WHERE source LIKE 'user%apple%watch';
