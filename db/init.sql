CREATE TABLE IF NOT EXISTS weight_records (
    id UUID PRIMARY KEY,
    source_package TEXT NOT NULL,
    recorded_time TIMESTAMPTZ NOT NULL,
    weight_kg NUMERIC(6,2) NOT NULL,
    raw JSONB NOT NULL,
    inserted_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS nutrition_records (
    id UUID PRIMARY KEY,
    source_package TEXT NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    name TEXT,
    energy_kcal NUMERIC(8,2),
    protein_g NUMERIC(8,2),
    carbs_g NUMERIC(8,2),
    fat_g NUMERIC(8,2),
    raw JSONB NOT NULL,
    inserted_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS exercise_records (
    id UUID PRIMARY KEY,
    source_package TEXT NOT NULL,
    exercise_type_code INTEGER,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    raw JSONB NOT NULL,
    inserted_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_weight_recorded_time ON weight_records (recorded_time);
CREATE INDEX IF NOT EXISTS idx_nutrition_start_time ON nutrition_records (start_time);
CREATE INDEX IF NOT EXISTS idx_exercise_start_time ON exercise_records (start_time);
