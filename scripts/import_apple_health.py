#!/usr/bin/env python3
"""One-shot import of Apple Health export.xml into Postgres."""

import argparse
import csv
import hashlib
import os
import time
import uuid
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from xml.etree.ElementTree import iterparse

import psycopg2
import psycopg2.extras

DB_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql://hcuser:hcpassword@localhost:5432/health_dashboard",
)

BATCH_SIZE = 1000

SKIP_TYPES = {
    "HKQuantityTypeIdentifierWalkingSpeed",
    "HKQuantityTypeIdentifierWalkingStepLength",
    "HKQuantityTypeIdentifierWalkingDoubleSupportPercentage",
    "HKQuantityTypeIdentifierWalkingAsymmetryPercentage",
    "HKQuantityTypeIdentifierAppleStandTime",
    "HKCategoryTypeIdentifierAppleStandHour",
    "HKQuantityTypeIdentifierHeadphoneAudioExposure",
    "HKQuantityTypeIdentifierEnvironmentalAudioExposure",
    "HKCategoryTypeIdentifierHeadphoneAudioExposureEvent",
    "HKCategoryTypeIdentifierAudioExposureEvent",
    "HKQuantityTypeIdentifierTimeInDaylight",
    "HKQuantityTypeIdentifierStairAscentSpeed",
    "HKQuantityTypeIdentifierStairDescentSpeed",
    "HKQuantityTypeIdentifierAppleWalkingSteadiness",
    "HKCategoryTypeIdentifierEnvironmentalAudioExposureEvent",
    "HKDataTypeSleepDurationGoal",
    "HKQuantityTypeIdentifierUVExposure",
}

SLEEP_TYPE = "HKCategoryTypeIdentifierSleepAnalysis"
FOOD_CORRELATION = "HKCorrelationTypeIdentifierFood"

NUTRIENT_MAP = {
    "HKQuantityTypeIdentifierDietaryEnergyConsumed":      "energy_kcal",
    "HKQuantityTypeIdentifierDietaryProtein":             "protein_g",
    "HKQuantityTypeIdentifierDietaryCarbohydrates":       "carbs_g",
    "HKQuantityTypeIdentifierDietaryFatTotal":            "fat_total_g",
    "HKQuantityTypeIdentifierDietaryFatSaturated":        "fat_saturated_g",
    "HKQuantityTypeIdentifierDietaryFatPolyunsaturated":  "fat_polyunsaturated_g",
    "HKQuantityTypeIdentifierDietaryFatMonounsaturated":  "fat_monounsaturated_g",
    "HKQuantityTypeIdentifierDietaryFiber":               "fiber_g",
    "HKQuantityTypeIdentifierDietarySugar":               "sugar_g",
    "HKQuantityTypeIdentifierDietaryCholesterol":         "cholesterol_mg",
    "HKQuantityTypeIdentifierDietarySodium":              "sodium_mg",
    "HKQuantityTypeIdentifierDietaryPotassium":           "potassium_mg",
    "HKQuantityTypeIdentifierDietaryCalcium":             "calcium_mg",
    "HKQuantityTypeIdentifierDietaryIron":                "iron_mg",
    "HKQuantityTypeIdentifierDietaryMagnesium":           "magnesium_mg",
    "HKQuantityTypeIdentifierDietaryPhosphorus":          "phosphorus_mg",
    "HKQuantityTypeIdentifierDietaryZinc":                "zinc_mg",
    "HKQuantityTypeIdentifierDietaryCopper":              "copper_mg",
    "HKQuantityTypeIdentifierDietaryManganese":           "manganese_mg",
    "HKQuantityTypeIdentifierDietarySelenium":            "selenium_mcg",
    "HKQuantityTypeIdentifierDietaryVitaminA":            "vitamin_a_mcg",
    "HKQuantityTypeIdentifierDietaryVitaminB6":           "vitamin_b6_mg",
    "HKQuantityTypeIdentifierDietaryVitaminB12":          "vitamin_b12_mcg",
    "HKQuantityTypeIdentifierDietaryVitaminC":            "vitamin_c_mg",
    "HKQuantityTypeIdentifierDietaryVitaminD":            "vitamin_d_mcg",
    "HKQuantityTypeIdentifierDietaryVitaminE":            "vitamin_e_mg",
    "HKQuantityTypeIdentifierDietaryVitaminK":            "vitamin_k_mcg",
    "HKQuantityTypeIdentifierDietaryRiboflavin":          "riboflavin_mg",
    "HKQuantityTypeIdentifierDietaryNiacin":              "niacin_mg",
    "HKQuantityTypeIdentifierDietaryThiamin":             "thiamin_mg",
    "HKQuantityTypeIdentifierDietaryPantothenicAcid":     "pantothenic_acid_mg",
    "HKQuantityTypeIdentifierDietaryFolate":              "folate_mcg",
    "HKQuantityTypeIdentifierDietaryCaffeine":            "caffeine_mg",
    "HKQuantityTypeIdentifierDietaryWater":               "water_ml",
}

WORKOUT_HR_TYPE     = "HKQuantityTypeIdentifierHeartRate"
WORKOUT_ACTIVE_E    = "HKQuantityTypeIdentifierActiveEnergyBurned"
WORKOUT_BASAL_E     = "HKQuantityTypeIdentifierBasalEnergyBurned"
WORKOUT_DIST_WALK   = "HKQuantityTypeIdentifierDistanceWalkingRunning"
WORKOUT_DIST_CYCLE  = "HKQuantityTypeIdentifierDistanceCycling"
WORKOUT_DIST_SWIM   = "HKQuantityTypeIdentifierDistanceSwimming"


def make_id(*parts) -> str:
    key = "|".join(str(p) for p in parts)
    digest = hashlib.sha256(key.encode()).digest()[:16]
    return str(uuid.UUID(bytes=digest))


def parse_dt(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S %z")


def flush(conn, sql: str, batch: list):
    if not batch:
        return
    cur = conn.cursor()
    psycopg2.extras.execute_values(cur, sql, batch, page_size=BATCH_SIZE)
    conn.commit()
    cur.close()
    batch.clear()


QTY_SQL = """
    INSERT INTO apple_health_quantity
        (id, type, source_name, unit, start_time, end_time, value, device)
    VALUES %s
    ON CONFLICT (id) DO NOTHING
"""

SLEEP_SQL = """
    INSERT INTO apple_health_sleep
        (id, source_name, start_time, end_time, stage, device)
    VALUES %s
    ON CONFLICT (id) DO NOTHING
"""

WORKOUT_SQL = """
    INSERT INTO apple_health_workouts
        (id, activity_type, source_name, start_time, end_time, duration_min,
         total_energy_kcal, total_distance_km,
         hr_avg, hr_min, hr_max, active_energy_kcal, basal_energy_kcal,
         gpx_file, metadata, device)
    VALUES %s
    ON CONFLICT (id) DO NOTHING
"""

NUTRITION_SQL = """
    INSERT INTO apple_health_nutrition
        (id, source_name, start_time, end_time,
         energy_kcal, protein_g, carbs_g, fat_total_g, fat_saturated_g,
         fat_polyunsaturated_g, fat_monounsaturated_g, fiber_g, sugar_g,
         cholesterol_mg, sodium_mg, potassium_mg, calcium_mg, iron_mg,
         magnesium_mg, phosphorus_mg, zinc_mg, copper_mg, manganese_mg,
         selenium_mcg, vitamin_a_mcg, vitamin_b6_mg, vitamin_b12_mcg,
         vitamin_c_mg, vitamin_d_mcg, vitamin_e_mg, vitamin_k_mcg,
         riboflavin_mg, niacin_mg, thiamin_mg, pantothenic_acid_mg,
         folate_mcg, caffeine_mg, water_ml)
    VALUES %s
    ON CONFLICT (id) DO NOTHING
"""

ECG_SQL = """
    INSERT INTO apple_health_ecg
        (id, recorded_date, classification, symptoms, device,
         software_version, sample_rate_hz, sample_count, file_path)
    VALUES %s
    ON CONFLICT (id) DO NOTHING
"""


def nutrition_row(attrs, nutrients):
    g = nutrients.get
    return (
        make_id(FOOD_CORRELATION, attrs.get("sourceName",""), attrs.get("startDate",""), attrs.get("endDate","")),
        attrs.get("sourceName",""),
        parse_dt(attrs["startDate"]),
        parse_dt(attrs["endDate"]),
        g("energy_kcal"), g("protein_g"), g("carbs_g"),
        g("fat_total_g"), g("fat_saturated_g"), g("fat_polyunsaturated_g"), g("fat_monounsaturated_g"),
        g("fiber_g"), g("sugar_g"), g("cholesterol_mg"), g("sodium_mg"), g("potassium_mg"),
        g("calcium_mg"), g("iron_mg"), g("magnesium_mg"), g("phosphorus_mg"),
        g("zinc_mg"), g("copper_mg"), g("manganese_mg"), g("selenium_mcg"),
        g("vitamin_a_mcg"), g("vitamin_b6_mg"), g("vitamin_b12_mcg"), g("vitamin_c_mg"),
        g("vitamin_d_mcg"), g("vitamin_e_mg"), g("vitamin_k_mcg"),
        g("riboflavin_mg"), g("niacin_mg"), g("thiamin_mg"), g("pantothenic_acid_mg"),
        g("folate_mcg"), g("caffeine_mg"), g("water_ml"),
    )


def fv(s):
    """Float or None."""
    try:
        return float(s) if s is not None else None
    except (ValueError, TypeError):
        return None


def parse_xml(xml_path: Path, conn):
    qty_batch, sleep_batch, workout_batch, nutrition_batch = [], [], [], []
    total = 0

    in_correlation = False
    corr_attrs = {}
    corr_nutrients: dict = {}

    current_workout = None
    workout_stats: dict = {}
    workout_meta: dict = {}
    workout_gpx = None

    for event, elem in iterparse(str(xml_path), events=("start", "end")):

        if event == "start":
            if elem.tag == "Correlation" and elem.get("type") == FOOD_CORRELATION:
                in_correlation = True
                corr_attrs = dict(elem.attrib)
                corr_nutrients = {}

            elif elem.tag == "Workout":
                current_workout = dict(elem.attrib)
                workout_stats = {}
                workout_meta = {}
                workout_gpx = None

        elif event == "end":

            if elem.tag == "Record":
                rtype = elem.get("type", "")

                if in_correlation:
                    col = NUTRIENT_MAP.get(rtype)
                    if col:
                        corr_nutrients[col] = fv(elem.get("value"))
                    elem.clear()
                    continue

                if rtype in SKIP_TYPES or rtype.startswith("HKQuantityTypeIdentifierDietary"):
                    elem.clear()
                    continue

                if rtype == SLEEP_TYPE:
                    sleep_batch.append((
                        make_id(SLEEP_TYPE, elem.get("sourceName",""), elem.get("startDate",""), elem.get("endDate",""), elem.get("value","")),
                        elem.get("sourceName",""),
                        parse_dt(elem.get("startDate","")),
                        parse_dt(elem.get("endDate","")),
                        elem.get("value",""),
                        elem.get("device"),
                    ))
                    if len(sleep_batch) >= BATCH_SIZE:
                        flush(conn, SLEEP_SQL, sleep_batch)
                else:
                    raw_val = elem.get("value")
                    val = fv(raw_val)
                    if val is None:
                        elem.clear()
                        continue
                    qty_batch.append((
                        make_id(rtype, elem.get("sourceName",""), elem.get("startDate",""), elem.get("endDate",""), raw_val),
                        rtype,
                        elem.get("sourceName",""),
                        elem.get("unit",""),
                        parse_dt(elem.get("startDate","")),
                        parse_dt(elem.get("endDate","")),
                        val,
                        elem.get("device"),
                    ))
                    if len(qty_batch) >= BATCH_SIZE:
                        flush(conn, QTY_SQL, qty_batch)

                total += 1
                if total % 10_000 == 0:
                    print(f"  {total:,} records processed…")
                elem.clear()

            elif elem.tag == "Correlation":
                if in_correlation and corr_attrs:
                    try:
                        nutrition_batch.append(nutrition_row(corr_attrs, corr_nutrients))
                        if len(nutrition_batch) >= BATCH_SIZE:
                            flush(conn, NUTRITION_SQL, nutrition_batch)
                    except Exception as e:
                        print(f"  Skipping food correlation: {e}")
                in_correlation = False
                corr_attrs = {}
                corr_nutrients = {}
                elem.clear()

            elif elem.tag == "WorkoutStatistics" and current_workout is not None:
                stype = elem.get("type","")
                if stype == WORKOUT_HR_TYPE:
                    workout_stats["hr_avg"] = fv(elem.get("average"))
                    workout_stats["hr_min"] = fv(elem.get("minimum"))
                    workout_stats["hr_max"] = fv(elem.get("maximum"))
                elif stype == WORKOUT_ACTIVE_E:
                    workout_stats["active_energy_kcal"] = fv(elem.get("sum"))
                elif stype == WORKOUT_BASAL_E:
                    workout_stats["basal_energy_kcal"] = fv(elem.get("sum"))
                elif stype in (WORKOUT_DIST_WALK, WORKOUT_DIST_CYCLE, WORKOUT_DIST_SWIM):
                    workout_stats["total_distance_km"] = fv(elem.get("sum"))
                elem.clear()

            elif elem.tag == "MetadataEntry" and current_workout is not None:
                k, v = elem.get("key"), elem.get("value")
                if k:
                    workout_meta[k] = v
                elem.clear()

            elif elem.tag == "FileReference" and current_workout is not None:
                workout_gpx = elem.get("path")
                elem.clear()

            elif elem.tag == "Workout" and current_workout is not None:
                try:
                    w = current_workout
                    workout_batch.append((
                        make_id(w.get("workoutActivityType",""), w.get("sourceName",""), w.get("startDate",""), w.get("endDate","")),
                        w.get("workoutActivityType",""),
                        w.get("sourceName",""),
                        parse_dt(w["startDate"]),
                        parse_dt(w["endDate"]),
                        fv(w.get("duration")),
                        fv(w.get("totalEnergyBurned")) or workout_stats.get("active_energy_kcal"),
                        workout_stats.get("total_distance_km"),
                        workout_stats.get("hr_avg"),
                        workout_stats.get("hr_min"),
                        workout_stats.get("hr_max"),
                        workout_stats.get("active_energy_kcal"),
                        workout_stats.get("basal_energy_kcal"),
                        workout_gpx,
                        psycopg2.extras.Json(workout_meta) if workout_meta else None,
                        w.get("device"),
                    ))
                    if len(workout_batch) >= BATCH_SIZE:
                        flush(conn, WORKOUT_SQL, workout_batch)
                except Exception as e:
                    print(f"  Skipping workout: {e}")
                current_workout = None
                elem.clear()

    flush(conn, QTY_SQL, qty_batch)
    flush(conn, SLEEP_SQL, sleep_batch)
    flush(conn, WORKOUT_SQL, workout_batch)
    flush(conn, NUTRITION_SQL, nutrition_batch)
    print(f"  {total:,} records total")


def parse_ecg_files(ecg_dir: Path, conn):
    files = sorted(ecg_dir.glob("*.csv"))
    if not files:
        print("No ECG CSV files found, skipping.")
        return

    rows = []
    for path in files:
        meta = {}
        samples = 0
        past_header = False
        with open(path, newline="", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    past_header = True
                    continue
                if past_header:
                    try:
                        float(line)
                        samples += 1
                    except ValueError:
                        pass
                else:
                    if "," in line:
                        parts = line.split(",", 1)
                        meta[parts[0].strip()] = parts[1].strip().strip('"')

        recorded_raw = meta.get("Recorded Date","")
        try:
            recorded_dt = datetime.strptime(recorded_raw, "%Y-%m-%d %H:%M:%S %z")
        except ValueError:
            print(f"  Could not parse ECG date '{recorded_raw}', skipping {path.name}")
            continue

        sample_rate = None
        sr_raw = meta.get("Sample Rate","")
        if sr_raw:
            try:
                sample_rate = int(sr_raw.split()[0])
            except (ValueError, IndexError):
                pass

        rows.append((
            make_id("ECG", path.name, recorded_raw, "", str(samples)),
            recorded_dt,
            meta.get("Classification"),
            meta.get("Symptoms") or None,
            meta.get("Device"),
            meta.get("Software Version"),
            sample_rate,
            samples if samples else None,
            str(path),
        ))

    if rows:
        flush(conn, ECG_SQL, rows)
        print(f"  {len(rows)} ECG file(s) imported")


def run_migration(conn, migration_path: Path):
    sql = migration_path.read_text()
    cur = conn.cursor()
    cur.execute(sql)
    conn.commit()
    cur.close()
    print("Migration applied.")


def print_summary(conn):
    tables = [
        "apple_health_quantity",
        "apple_health_sleep",
        "apple_health_workouts",
        "apple_health_nutrition",
        "apple_health_ecg",
    ]
    cur = conn.cursor()
    print("\n── Import summary ─────────────────────────")
    for t in tables:
        cur.execute(f"SELECT COUNT(*) FROM {t}")
        count = cur.fetchone()[0]
        print(f"  {t:<30} {count:>10,}")
    cur.close()
    print("───────────────────────────────────────────")


def main():
    parser = argparse.ArgumentParser(description="Import Apple Health export into Postgres")
    parser.add_argument("--xml", default="apple_health_export/export.xml", help="Path to export.xml")
    parser.add_argument("--ecg-dir", default="apple_health_export/electrocardiograms", help="ECG CSV directory")
    parser.add_argument("--db", default=DB_URL, help="Postgres connection URL")
    parser.add_argument("--skip-migration", action="store_true", help="Skip running the DDL migration")
    args = parser.parse_args()

    xml_path = Path(args.xml)
    ecg_dir = Path(args.ecg_dir)
    migration_path = Path(__file__).parent.parent / "db" / "migrations" / "001_apple_health.sql"

    if not xml_path.exists():
        print(f"ERROR: {xml_path} not found")
        return

    conn = psycopg2.connect(args.db)

    if not args.skip_migration:
        print("Running migration…")
        run_migration(conn, migration_path)

    print(f"Parsing {xml_path} …")
    t0 = time.time()
    parse_xml(xml_path, conn)
    print(f"XML parse done in {time.time() - t0:.0f}s")

    if ecg_dir.exists():
        print("Importing ECG files…")
        parse_ecg_files(ecg_dir, conn)

    print_summary(conn)
    conn.close()


if __name__ == "__main__":
    main()
