#!/usr/bin/env python3
"""Import MacroFactor daily CSV export into macrofactor_daily table."""

import argparse
import csv
import os
from datetime import datetime
from pathlib import Path

import psycopg2
import psycopg2.extras

DB_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql://hcuser:hcpassword@localhost:5432/health_dashboard",
)

# CSV header -> (db_column, type)  type: 'd'=date, 'f'=float, 'i'=int
COLUMN_MAP = [
    ("Date",                    "date",                  "d"),
    ("Expenditure",             "expenditure_kcal",      "f"),
    ("Trend Weight (kg)",       "trend_weight_kg",       "f"),
    ("Weight (kg)",             "weight_kg",             "f"),
    ("Calories (kcal)",         "calories_kcal",         "f"),
    ("Protein (g)",             "protein_g",             "f"),
    ("Fat (g)",                 "fat_g",                 "f"),
    ("Carbs (g)",               "carbs_g",               "f"),
    ("Target Calories (kcal)",  "target_calories_kcal",  "f"),
    ("Target Protein (g)",      "target_protein_g",      "f"),
    ("Target Fat (g)",          "target_fat_g",          "f"),
    ("Target Carbs (g)",        "target_carbs_g",        "f"),
    ("Steps",                   "steps",                 "i"),
    ("Alcohol (g)",             "alcohol_g",             "f"),
    ("B12, Cobalamin (mcg)",    "vitamin_b12_mcg",       "f"),
    ("B1, Thiamine (mg)",       "thiamin_mg",            "f"),
    ("B2, Riboflavin (mg)",     "riboflavin_mg",         "f"),
    ("B3, Niacin (mg)",         "niacin_mg",             "f"),
    ("B5, Pantothenic Acid (mg)","pantothenic_acid_mg",  "f"),
    ("B6, Pyridoxine (mg)",     "vitamin_b6_mg",         "f"),
    ("Caffeine (mg)",           "caffeine_mg",           "f"),
    ("Calcium (mg)",            "calcium_mg",            "f"),
    ("Cholesterol (mg)",        "cholesterol_mg",        "f"),
    ("Choline (mg)",            "choline_mg",            "f"),
    ("Copper (mg)",             "copper_mg",             "f"),
    ("Cysteine (g)",            "cysteine_g",            "f"),
    ("Monounsaturated Fat (g)", "fat_monounsaturated_g", "f"),
    ("Polyunsaturated Fat (g)", "fat_polyunsaturated_g", "f"),
    ("Saturated Fat (g)",       "fat_saturated_g",       "f"),
    ("Trans Fat (g)",           "fat_trans_g",           "f"),
    ("Fiber (g)",               "fiber_g",               "f"),
    ("Folate (mcg)",            "folate_mcg",            "f"),
    ("Histidine (g)",           "histidine_g",           "f"),
    ("Iron (mg)",               "iron_mg",               "f"),
    ("Isoleucine (g)",          "isoleucine_g",          "f"),
    ("Leucine (g)",             "leucine_g",             "f"),
    ("Lysine (g)",              "lysine_g",              "f"),
    ("Magnesium (mg)",          "magnesium_mg",          "f"),
    ("Manganese (mg)",          "manganese_mg",          "f"),
    ("Methionine (g)",          "methionine_g",          "f"),
    ("Omega-3 ALA (g)",         "omega3_ala_g",          "f"),
    ("Omega-3 DHA (g)",         "omega3_dha_g",          "f"),
    ("Omega-3 EPA (g)",         "omega3_epa_g",          "f"),
    ("Omega-3 (g)",             "omega3_total_g",        "f"),
    ("Omega-6 (g)",             "omega6_g",              "f"),
    ("Phenylalanine (g)",       "phenylalanine_g",       "f"),
    ("Phosphorus (mg)",         "phosphorus_mg",         "f"),
    ("Potassium (mg)",          "potassium_mg",          "f"),
    ("Selenium (mcg)",          "selenium_mcg",          "f"),
    ("Sodium (mg)",             "sodium_mg",             "f"),
    ("Starch (g)",              "starch_g",              "f"),
    ("Sugars (g)",              "sugars_g",              "f"),
    ("Sugars Added (g)",        "sugars_added_g",        "f"),
    ("Threonine (g)",           "threonine_g",           "f"),
    ("Tryptophan (g)",          "tryptophan_g",          "f"),
    ("Tyrosine (g)",            "tyrosine_g",            "f"),
    ("Valine (g)",              "valine_g",              "f"),
    ("Vitamin A (mcg)",         "vitamin_a_mcg",         "f"),
    ("Vitamin C (mg)",          "vitamin_c_mg",          "f"),
    ("Vitamin D (mcg)",         "vitamin_d_mcg",         "f"),
    ("Vitamin E (mg)",          "vitamin_e_mg",          "f"),
    ("Vitamin K (mcg)",         "vitamin_k_mcg",         "f"),
    ("Water (g)",               "water_g",               "f"),
    ("Zinc (mg)",               "zinc_mg",               "f"),
]

DB_COLS = [col for _, col, _ in COLUMN_MAP]

INSERT_SQL = f"""
    INSERT INTO macrofactor_daily ({", ".join(DB_COLS)})
    VALUES %s
    ON CONFLICT (date) DO UPDATE SET
        {", ".join(f"{c} = EXCLUDED.{c}" for c in DB_COLS if c != "date")}
"""


def coerce(value: str, kind: str):
    v = value.strip()
    if not v:
        return None
    if kind == "d":
        return datetime.strptime(v, "%m/%d/%Y").date()
    if kind == "i":
        return int(float(v))
    return float(v)


def main():
    parser = argparse.ArgumentParser(description="Import MacroFactor daily CSV")
    parser.add_argument("csv", help="Path to MacroFactor CSV export")
    parser.add_argument("--db", default=DB_URL)
    parser.add_argument("--skip-migration", action="store_true")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    migration_path = Path(__file__).parent.parent / "db" / "migrations" / "002_macrofactor_daily.sql"

    conn = psycopg2.connect(args.db)
    cur = conn.cursor()

    if not args.skip_migration:
        cur.execute(migration_path.read_text())
        conn.commit()
        print("Migration applied.")

    rows = []
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                rows.append(tuple(coerce(row.get(csv_col, ""), kind) for csv_col, _, kind in COLUMN_MAP))
            except Exception as e:
                print(f"  Skipping row {row.get('Date','?')}: {e}")

    if rows:
        psycopg2.extras.execute_values(cur, INSERT_SQL, rows)
        conn.commit()
        print(f"Imported {len(rows)} days.")
    else:
        print("No rows imported.")

    cur.execute("SELECT MIN(date), MAX(date), COUNT(*) FROM macrofactor_daily")
    mn, mx, cnt = cur.fetchone()
    print(f"macrofactor_daily: {cnt} rows, {mn} → {mx}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
