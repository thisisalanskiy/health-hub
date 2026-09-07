CREATE TABLE IF NOT EXISTS apple_health_quantity (
    id          UUID PRIMARY KEY,
    type        TEXT          NOT NULL,
    source_name TEXT          NOT NULL,
    unit        TEXT          NOT NULL,
    start_time  TIMESTAMPTZ   NOT NULL,
    end_time    TIMESTAMPTZ   NOT NULL,
    value       NUMERIC(14,6) NOT NULL,
    device      TEXT,
    inserted_at TIMESTAMPTZ   DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ahq_type_start ON apple_health_quantity (type, start_time);
CREATE INDEX IF NOT EXISTS idx_ahq_start_time  ON apple_health_quantity (start_time);

CREATE TABLE IF NOT EXISTS apple_health_sleep (
    id           UUID PRIMARY KEY,
    source_name  TEXT        NOT NULL,
    start_time   TIMESTAMPTZ NOT NULL,
    end_time     TIMESTAMPTZ NOT NULL,
    stage        TEXT        NOT NULL,
    duration_min NUMERIC(8,2) GENERATED ALWAYS AS
                     (EXTRACT(EPOCH FROM (end_time - start_time)) / 60.0) STORED,
    device       TEXT,
    inserted_at  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ahs_start_time ON apple_health_sleep (start_time);
CREATE INDEX IF NOT EXISTS idx_ahs_stage      ON apple_health_sleep (stage, start_time);

CREATE TABLE IF NOT EXISTS apple_health_workouts (
    id                 UUID PRIMARY KEY,
    activity_type      TEXT        NOT NULL,
    source_name        TEXT        NOT NULL,
    start_time         TIMESTAMPTZ NOT NULL,
    end_time           TIMESTAMPTZ NOT NULL,
    duration_min       NUMERIC(8,3),
    total_energy_kcal  NUMERIC(10,3),
    total_distance_km  NUMERIC(10,6),
    hr_avg             NUMERIC(6,2),
    hr_min             NUMERIC(6,2),
    hr_max             NUMERIC(6,2),
    active_energy_kcal NUMERIC(10,3),
    basal_energy_kcal  NUMERIC(10,3),
    gpx_file           TEXT,
    metadata           JSONB,
    device             TEXT,
    inserted_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ahw_start_time    ON apple_health_workouts (start_time);
CREATE INDEX IF NOT EXISTS idx_ahw_activity_type ON apple_health_workouts (activity_type, start_time);

CREATE TABLE IF NOT EXISTS apple_health_nutrition (
    id                    UUID PRIMARY KEY,
    source_name           TEXT        NOT NULL,
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    energy_kcal           NUMERIC(10,3),
    protein_g             NUMERIC(10,4),
    carbs_g               NUMERIC(10,4),
    fat_total_g           NUMERIC(10,4),
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
    inserted_at           TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ahn_start_time ON apple_health_nutrition (start_time);

CREATE TABLE IF NOT EXISTS apple_health_ecg (
    id               UUID PRIMARY KEY,
    recorded_date    TIMESTAMPTZ NOT NULL,
    classification   TEXT,
    symptoms         TEXT,
    device           TEXT,
    software_version TEXT,
    sample_rate_hz   INTEGER,
    sample_count     INTEGER,
    file_path        TEXT NOT NULL,
    inserted_at      TIMESTAMPTZ DEFAULT now()
);
