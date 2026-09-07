import http.client
import os
import re
import socket as _socket
import struct
import uuid as _uuid
from datetime import date, datetime, timedelta, timezone

import psycopg2
import psycopg2.extras
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse

from tdee import compute_tdee_series

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql://hcuser:hcpassword@localhost:5432/health_dashboard",
)

app = FastAPI(title="Health Connect Ingestion API")

# Health Connect exercise type code → human label
HC_EXERCISE_TYPES = {
    2: "Badminton", 4: "Baseball", 5: "Basketball", 8: "Biking",
    9: "Biking Stationary", 10: "Boot Camp", 11: "Boxing", 13: "Calisthenics",
    14: "Cricket", 16: "Dancing", 21: "Elliptical", 22: "Exercise Class",
    25: "Football American", 26: "Frisbee", 27: "Golf", 28: "Guided Breathing",
    29: "Gymnastics", 31: "HIIT", 32: "Hiking", 36: "Martial Arts",
    37: "Paddling", 39: "Pilates", 43: "Rowing", 44: "Rowing Machine",
    45: "Rugby", 56: "Running", 57: "Running Treadmill", 61: "Skiing",
    62: "Snowboarding", 64: "Soccer", 66: "Squash", 67: "Stair Climbing",
    69: "Strength Training", 70: "Stretching", 71: "Surfing",
    72: "Swimming Open Water", 73: "Swimming Pool", 74: "Table Tennis",
    75: "Tennis", 76: "Volleyball", 79: "Walking", 80: "Water Polo",
    81: "Weightlifting", 82: "Wheelchair", 83: "Yoga",
}

# HC sleep stage int → HK-compatible stage name (unified with AH data)
HC_SLEEP_STAGES = {
    1: "HKCategoryValueSleepAnalysisAwake",
    2: "HKCategoryValueSleepAnalysisAsleepUnspecified",
    3: "HKCategoryValueSleepAnalysisAwake",       # out of bed
    4: "HKCategoryValueSleepAnalysisAsleepCore",
    5: "HKCategoryValueSleepAnalysisAsleepDeep",
    6: "HKCategoryValueSleepAnalysisAsleepREM",
}

# Android package name → human-readable source label
PACKAGE_SOURCE = {
    "com.whoop.android":                     "WHOOP",
    "com.oceanwing.smarthome":               "Smart Scale",
    "com.sbs.diet":                          "Diet App",
    "com.garmin.android.apps.connectmobile": "Garmin",
    "fi.polar.polarflow":                    "Polar",
}


def normalise_source(package: str) -> str:
    if package in PACKAGE_SOURCE:
        return PACKAGE_SOURCE[package]
    if "healthconnect" in package:
        return "Health Connect"
    # Best-effort: last segment of reverse-domain, title-cased
    return package.split(".")[-1].replace("_", " ").title()


def get_conn():
    return psycopg2.connect(DATABASE_URL)


def ms_to_dt(ms):
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)


@app.get("/healthz")
def health():
    return {"status": "ok"}


_ALL_TYPES = ("weight", "nutrition", "exercise", "heartRate", "restingHeartRate", "steps", "sleep")


def _detect_type(r: dict) -> str:
    if "weight" in r and "time" in r:
        return "weight"
    if "exerciseType" in r:
        return "exercise"
    if "samples" in r:
        return "heartRate"
    if "beatsPerMinute" in r:
        return "restingHeartRate"
    if "count" in r and "startTime" in r:
        return "steps"
    if "stages" in r:
        return "sleep"
    return "nutrition"


def _extract_records(payload: dict) -> dict:
    if any(k in payload for k in _ALL_TYPES):
        return {k: (payload.get(k) or {}).get("records", []) for k in _ALL_TYPES}
    raw_records = (
        payload.get("records")
        or (payload.get("data") or {}).get("records")
        or []
    )
    buckets: dict = {k: [] for k in _ALL_TYPES}
    for r in raw_records:
        buckets[_detect_type(r)].append(r)
    return buckets


@app.post("/ingest")
async def ingest(request: Request):
    payload = await request.json()
    buckets = _extract_records(payload)
    conn = get_conn()
    cur = conn.cursor()
    counts = {"weight": 0, "nutrition": 0, "exercise": 0,
              "heartRate": 0, "restingHeartRate": 0, "steps": 0, "sleep": 0, "skipped": 0}

    # --- Weight → weight_logs ---
    for r in buckets["weight"]:
        try:
            cur.execute(
                """
                INSERT INTO weight_logs (id, recorded_at, weight_kg, source, raw)
                VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (source, recorded_at) DO UPDATE SET
                    weight_kg = EXCLUDED.weight_kg,
                    raw       = EXCLUDED.raw
                """,
                (
                    r["metadata"]["id"],
                    ms_to_dt(r["time"]),
                    r["weight"]["kilograms"],
                    normalise_source(r["metadata"]["dataOrigin"]["packageName"]),
                    psycopg2.extras.Json(r),
                ),
            )
            counts["weight"] += 1
        except Exception as e:
            print(f"Skipping weight record ({e})")
            counts["skipped"] += 1

    # --- Nutrition → meal_logs ---
    for r in buckets["nutrition"]:
        try:
            energy  = (r.get("energy")             or {}).get("kilocalories")
            protein = (r.get("protein")             or {}).get("grams")
            carbs   = (r.get("totalCarbohydrate")   or {}).get("grams")
            fat     = (r.get("totalFat")            or {}).get("grams")
            cur.execute(
                """
                INSERT INTO meal_logs
                    (id, logged_at, end_time, name, energy_kcal,
                     protein_g, carbs_g, fat_g, source, raw)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (source, logged_at) DO UPDATE SET
                    energy_kcal = EXCLUDED.energy_kcal,
                    protein_g   = EXCLUDED.protein_g,
                    carbs_g     = EXCLUDED.carbs_g,
                    fat_g       = EXCLUDED.fat_g,
                    raw         = EXCLUDED.raw
                """,
                (
                    r["metadata"]["id"],
                    ms_to_dt(r["startTime"]),
                    ms_to_dt(r["endTime"]),
                    r.get("name"),
                    energy, protein, carbs, fat,
                    normalise_source(r["metadata"]["dataOrigin"]["packageName"]),
                    psycopg2.extras.Json(r),
                ),
            )
            counts["nutrition"] += 1
        except Exception as e:
            print(f"Skipping nutrition record ({e})")
            counts["skipped"] += 1

    # --- Exercise → workouts ---
    for r in buckets["exercise"]:
        try:
            code = r.get("exerciseType")
            start = ms_to_dt(r["startTime"])
            end   = ms_to_dt(r["endTime"])
            cur.execute(
                """
                INSERT INTO workouts
                    (id, activity_type, start_time, end_time, duration_min, source, raw)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (source, start_time) DO NOTHING
                """,
                (
                    r["metadata"]["id"],
                    HC_EXERCISE_TYPES.get(code, f"Type {code}"),
                    start, end,
                    round((end - start).total_seconds() / 60, 3),
                    normalise_source(r["metadata"]["dataOrigin"]["packageName"]),
                    psycopg2.extras.Json(r),
                ),
            )
            counts["exercise"] += 1
        except Exception as e:
            print(f"Skipping exercise record ({e})")
            counts["skipped"] += 1

    # --- HeartRate samples → biometric_timeseries ---
    for r in buckets["heartRate"]:
        pkg_raw = r["metadata"]["dataOrigin"]["packageName"]
        pkg     = normalise_source(pkg_raw)
        for sample in r.get("samples", []):
            try:
                t   = ms_to_dt(sample["time"])
                bpm = sample["beatsPerMinute"]
                sid = str(_uuid.uuid5(_uuid.NAMESPACE_URL, f"hc-hr-{r['metadata']['id']}-{sample['time']}"))
                cur.execute(
                    """
                    INSERT INTO biometric_timeseries
                        (id, type, source_name, unit, start_time, end_time, value, source)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    (sid, "HKQuantityTypeIdentifierHeartRate", pkg_raw, "count/min", t, t, bpm, pkg),
                )
                counts["heartRate"] += 1
            except Exception as e:
                print(f"Skipping HR sample ({e})")
                counts["skipped"] += 1

    # --- RestingHeartRate → biometric_timeseries ---
    for r in buckets["restingHeartRate"]:
        try:
            pkg_raw = r["metadata"]["dataOrigin"]["packageName"]
            t = ms_to_dt(r["time"])
            cur.execute(
                """
                INSERT INTO biometric_timeseries
                    (id, type, source_name, unit, start_time, end_time, value, source)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (id) DO NOTHING
                """,
                (r["metadata"]["id"], "HKQuantityTypeIdentifierRestingHeartRate",
                 pkg_raw, "count/min", t, t, r["beatsPerMinute"], normalise_source(pkg_raw)),
            )
            counts["restingHeartRate"] += 1
        except Exception as e:
            print(f"Skipping resting HR ({e})")
            counts["skipped"] += 1

    # --- Steps → biometric_timeseries ---
    for r in buckets["steps"]:
        try:
            pkg_raw = r["metadata"]["dataOrigin"]["packageName"]
            start, end = ms_to_dt(r["startTime"]), ms_to_dt(r["endTime"])
            cur.execute(
                """
                INSERT INTO biometric_timeseries
                    (id, type, source_name, unit, start_time, end_time, value, source)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (id) DO NOTHING
                """,
                (r["metadata"]["id"], "HKQuantityTypeIdentifierStepCount",
                 pkg_raw, "count", start, end, r["count"], normalise_source(pkg_raw)),
            )
            counts["steps"] += 1
        except Exception as e:
            print(f"Skipping steps ({e})")
            counts["skipped"] += 1

    # --- SleepSession stages → sleep_records ---
    for r in buckets["sleep"]:
        pkg_raw = r["metadata"]["dataOrigin"]["packageName"]
        pkg     = normalise_source(pkg_raw)
        stages  = r.get("stages") or []
        if not stages:
            # No stage breakdown — store the whole session as Sleeping
            try:
                sid   = str(_uuid.uuid5(_uuid.NAMESPACE_URL, f"hc-sleep-{r['metadata']['id']}"))
                start = ms_to_dt(r["startTime"])
                end   = ms_to_dt(r["endTime"])
                cur.execute(
                    """
                    INSERT INTO sleep_records (id, source_name, start_time, end_time, stage, source)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    (sid, pkg_raw, start, end, "HKCategoryValueSleepAnalysisAsleepUnspecified", pkg),
                )
                counts["sleep"] += 1
            except Exception as e:
                print(f"Skipping sleep session ({e})")
                counts["skipped"] += 1
        else:
            for i, stage in enumerate(stages):
                try:
                    sid   = str(_uuid.uuid5(_uuid.NAMESPACE_URL, f"hc-sleep-{r['metadata']['id']}-{i}"))
                    start = ms_to_dt(stage["startTime"])
                    end   = ms_to_dt(stage["endTime"])
                    name  = HC_SLEEP_STAGES.get(stage.get("stage", 0), "HKCategoryValueSleepAnalysisAsleepUnspecified")
                    cur.execute(
                        """
                        INSERT INTO sleep_records (id, source_name, start_time, end_time, stage, source)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        ON CONFLICT (id) DO NOTHING
                        """,
                        (sid, pkg_raw, start, end, name, pkg),
                    )
                    counts["sleep"] += 1
                except Exception as e:
                    print(f"Skipping sleep stage ({e})")
                    counts["skipped"] += 1

    conn.commit()
    cur.close()
    conn.close()

    return JSONResponse({"status": "ok", "ingested": counts})


@app.get("/dashboard", response_class=HTMLResponse)
def dashboard():
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        SELECT date, expenditure_kcal, trend_weight_kg, weight_kg,
               calories_kcal, protein_g, fat_g, carbs_g,
               target_calories_kcal, target_protein_g, target_fat_g, target_carbs_g, steps
        FROM macrofactor_daily ORDER BY date DESC LIMIT 50
    """)
    mf_rows = cur.fetchall()

    cur.execute("""
        SELECT recorded_at AT TIME ZONE 'UTC', weight_kg, source, inserted_at AT TIME ZONE 'UTC'
        FROM weight_logs ORDER BY recorded_at DESC LIMIT 50
    """)
    weight_rows = cur.fetchall()

    cur.execute("""
        SELECT logged_at AT TIME ZONE 'UTC', name,
               ROUND(energy_kcal,0), ROUND(protein_g,1), ROUND(carbs_g,1), ROUND(fat_g,1),
               source, inserted_at AT TIME ZONE 'UTC'
        FROM meal_logs ORDER BY logged_at DESC LIMIT 50
    """)
    meal_rows = cur.fetchall()

    cur.execute("""
        SELECT start_time AT TIME ZONE 'UTC', end_time AT TIME ZONE 'UTC',
               activity_type, ROUND(duration_min,1), ROUND(energy_kcal,0),
               ROUND(distance_km,2), source
        FROM workouts ORDER BY start_time DESC LIMIT 50
    """)
    workout_rows = cur.fetchall()

    cur.execute("""
        SELECT start_time AT TIME ZONE 'UTC', end_time AT TIME ZONE 'UTC',
               stage, ROUND(duration_min,1), source
        FROM sleep_records ORDER BY start_time DESC LIMIT 50
    """)
    sleep_rows = cur.fetchall()

    cur.execute("""
        SELECT start_time AT TIME ZONE 'UTC', value, unit, source, type
        FROM biometric_timeseries
        WHERE type = 'HKQuantityTypeIdentifierHeartRate'
        ORDER BY start_time DESC LIMIT 50
    """)
    hr_rows = cur.fetchall()

    cur.execute("""
        SELECT type, COUNT(*) AS n,
               MIN(start_time AT TIME ZONE 'UTC'),
               MAX(start_time AT TIME ZONE 'UTC')
        FROM biometric_timeseries
        GROUP BY type ORDER BY n DESC
    """)
    bts_summary = cur.fetchall()

    cur.execute("""
        SELECT recorded_date AT TIME ZONE 'UTC', classification,
               symptoms, device, sample_rate_hz, sample_count
        FROM ecg_records ORDER BY recorded_date DESC
    """)
    ecg_rows = cur.fetchall()

    cur.close()
    conn.close()

    def fmt(v):
        if v is None:
            return "<span style='color:#aaa'>—</span>"
        if hasattr(v, "strftime"):
            return v.strftime("%Y-%m-%d %H:%M")
        return str(v)

    def tbl(headers, rows, row_fn):
        ths = "".join(f"<th>{h}</th>" for h in headers)
        trs = "".join(f"<tr>{row_fn(r)}</tr>" for r in rows) or \
              "<tr><td colspan='99' style='color:#aaa;text-align:center'>no records</td></tr>"
        return f"<table><thead><tr>{ths}</tr></thead><tbody>{trs}</tbody></table>"

    def td(*vals):
        return "".join(f"<td>{fmt(v)}</td>" for v in vals)

    def short_type(t):
        return t.replace("HKQuantityTypeIdentifier", "").replace("HKCategoryTypeIdentifier", "")

    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Health Hub</title>
<style>
  body  {{ font-family: system-ui, sans-serif; padding: 2rem; background:#f8f8f8; color:#222; }}
  h1   {{ margin-bottom:.25rem; }}
  h2   {{ margin-top:2.5rem; padding-bottom:.3rem; border-bottom:2px solid #ddd; }}
  h3   {{ margin-top:1.5rem; margin-bottom:.4rem; color:#444; }}
  table{{ border-collapse:collapse; width:100%; background:#fff; font-size:.85rem; margin-bottom:1rem; }}
  th,td{{ border:1px solid #ddd; padding:.4rem .6rem; white-space:nowrap; }}
  th   {{ background:#f0f0f0; text-align:left; }}
  tr:hover td {{ background:#fffde7; }}
  .cnt {{ color:#888; font-weight:normal; font-size:.9rem; margin-left:.5rem; }}
  .s1  {{ border-left:4px solid #ff9800; padding-left:.75rem; }}
  .s2  {{ border-left:4px solid #4caf50; padding-left:.75rem; }}
  .s3  {{ border-left:4px solid #2196f3; padding-left:.75rem; }}
</style>
</head>
<body>
<h1>Health Hub <span style="color:#888;font-size:1rem">debug dashboard</span></h1>
<p style="color:#888;font-size:.85rem">Last 50 per table · UTC · <a href="/dashboard">refresh</a></p>

<h2 class="s1">MacroFactor — Daily Summary <span class="cnt">({len(mf_rows)} shown)</span></h2>
{tbl(["Date","TDEE kcal","Trend kg","Weight kg","Calories","Protein g","Fat g","Carbs g",
      "Target kcal","Target P","Target F","Target C","Steps"], mf_rows, lambda r: td(*r))}

<h2 class="s2">Unified — Weight Logs <span class="cnt">({len(weight_rows)} shown)</span></h2>
{tbl(["Recorded (UTC)","Weight kg","Source","Inserted (UTC)"],
     weight_rows, lambda r: td(*r))}

<h2 class="s2">Unified — Meal Logs <span class="cnt">({len(meal_rows)} shown)</span></h2>
{tbl(["Logged (UTC)","Name","kcal","Protein g","Carbs g","Fat g","Source","Inserted (UTC)"],
     meal_rows, lambda r: td(*r))}

<h2 class="s2">Unified — Workouts <span class="cnt">({len(workout_rows)} shown)</span></h2>
{tbl(["Start (UTC)","End (UTC)","Activity","Duration min","kcal","Distance km","Source"],
     workout_rows, lambda r: td(*r))}

<h2 class="s3">Sleep Records <span class="cnt">({len(sleep_rows)} shown)</span></h2>
{tbl(["Start (UTC)","End (UTC)","Stage","Duration min","Source"],
     sleep_rows, lambda r: td(*r))}

<h2 class="s3">Heart Rate (latest 50)</h2>
{tbl(["Time (UTC)","BPM","Unit","Source"], hr_rows, lambda r: td(r[0],r[1],r[2],r[3]))}

<h2 class="s3">ECG Records</h2>
{tbl(["Recorded (UTC)","Classification","Symptoms","Device","Sample Rate","Samples"],
     ecg_rows, lambda r: td(*r))}

<h2 class="s3">Biometric Timeseries — All Types</h2>
{tbl(["Type","Records","Earliest (UTC)","Latest (UTC)"], bts_summary,
     lambda r: f"<td>{short_type(r[0])}</td><td>{r[1]:,}</td><td>{fmt(r[2])}</td><td>{fmt(r[3])}</td>")}
</body>
</html>"""
    return HTMLResponse(html)


# ─────────────────────────────────────────────────────────────────
# Helpers for user-facing dashboard
# ─────────────────────────────────────────────────────────────────

TZ = os.environ.get("LOCAL_TZ", "UTC")


def jsonable(obj):
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    if isinstance(obj, list):
        return [jsonable(i) for i in obj]
    if isinstance(obj, dict):
        return {k: jsonable(v) for k, v in obj.items()}
    return obj


# ─────────────────────────────────────────────────────────────────
# JSON API endpoints
# ─────────────────────────────────────────────────────────────────

@app.get("/api/snapshot")
def api_snapshot():
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("SELECT weight_kg FROM weight_logs ORDER BY recorded_at DESC LIMIT 1")
    w = cur.fetchone()

    cur.execute(
        "SELECT calories_kcal, expenditure_kcal, steps FROM macrofactor_daily ORDER BY date DESC LIMIT 1"
    )
    mf = cur.fetchone()

    cur.execute("""
        SELECT SUM(value)::bigint
        FROM biometric_timeseries
        WHERE type = 'HKQuantityTypeIdentifierStepCount'
          AND DATE(start_time AT TIME ZONE 'UTC') = CURRENT_DATE
    """)
    bts_steps = cur.fetchone()

    cur.execute("""
        WITH ranked AS (
            SELECT source, SUM(duration_min) AS total_min,
                ROW_NUMBER() OVER (
                    ORDER BY CASE source
                        WHEN 'WHOOP'       THEN 1
                        WHEN 'Oura'        THEN 2
                        WHEN 'Ultrahuman'  THEN 3
                        WHEN 'AutoSleep'   THEN 4
                        WHEN 'Apple Watch' THEN 5
                        ELSE 6 END
                ) rn
            FROM sleep_records
            WHERE start_time >= CURRENT_DATE - INTERVAL '2 days'
              AND stage NOT IN (
                  'HKCategoryValueSleepAnalysisInBed',
                  'HKCategoryValueSleepAnalysisAwake',
                  'AWAKE'
              )
            GROUP BY source
        )
        SELECT total_min FROM ranked WHERE rn = 1
    """)
    sl = cur.fetchone()

    cur.close()
    conn.close()

    mf_steps    = int(mf[2])          if mf and mf[2]             else None
    live_steps  = int(bts_steps[0])   if bts_steps and bts_steps[0] else None
    steps_today = live_steps or mf_steps   # prefer live HC count, fall back to MacroFactor

    return JSONResponse({
        "weight_kg":     float(w[0])  if w             else None,
        "calories_kcal": float(mf[0]) if mf and mf[0] else None,
        "tdee_kcal":     float(mf[1]) if mf and mf[1] else None,
        "steps":         steps_today,
        "sleep_min":     float(sl[0]) if sl            else None,
    })


@app.get("/api/weight")
def api_weight(days: int = 30):
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        SELECT recorded_at, weight_kg, source
        FROM weight_logs
        WHERE recorded_at >= NOW() - INTERVAL '1 day' * %s
        ORDER BY recorded_at
    """, (days,))
    readings = [{"t": jsonable(r[0]), "v": float(r[1]), "source": r[2]}
                for r in cur.fetchall()]

    cur.execute("""
        SELECT date, trend_weight_kg
        FROM macrofactor_daily
        WHERE date >= CURRENT_DATE - INTERVAL '1 day' * %s
          AND trend_weight_kg IS NOT NULL
        ORDER BY date
    """, (days,))
    trend = [{"t": jsonable(r[0]), "v": float(r[1])} for r in cur.fetchall()]

    cur.close()
    conn.close()
    return JSONResponse({"readings": readings, "trend": trend})


@app.get("/api/recovery")
def api_recovery(days: int = 30):
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        SELECT date_trunc('day', start_time)::date AS day,
               AVG(value)::float, type
        FROM biometric_timeseries
        WHERE type IN (
            'HKQuantityTypeIdentifierRestingHeartRate',
            'HKQuantityTypeIdentifierHeartRateVariabilitySDNN'
        )
          AND start_time >= NOW() - INTERVAL '1 day' * %s
        GROUP BY 1, 3
        ORDER BY 1
    """, (days,))

    rhr_map, hrv_map = {}, {}
    for day, val, typ in cur.fetchall():
        key = jsonable(day)
        if "RestingHeartRate" in typ:
            rhr_map[key] = round(val, 1)
        else:
            hrv_map[key] = round(val, 1)

    cur.close()
    conn.close()

    all_days = sorted(set(rhr_map) | set(hrv_map))
    return JSONResponse({
        "rhr": [{"t": d, "v": rhr_map.get(d)} for d in all_days],
        "hrv": [{"t": d, "v": hrv_map.get(d)} for d in all_days],
    })


@app.get("/api/heartrate")
def api_heartrate(days: int = 30):
    conn = get_conn()
    cur  = conn.cursor()
    cur.execute("""
        SELECT DATE(start_time AT TIME ZONE 'UTC') AS day,
               MIN(value)::float,
               AVG(value)::float,
               MAX(value)::float
        FROM biometric_timeseries
        WHERE type       = 'HKQuantityTypeIdentifierHeartRate'
          AND start_time >= NOW() - INTERVAL '1 day' * %s
        GROUP BY 1
        ORDER BY 1
    """, (days,))
    rows = cur.fetchall()
    cur.close()
    conn.close()
    return JSONResponse({
        "days": [str(r[0]) for r in rows],
        "min":  [round(r[1], 1) for r in rows],
        "avg":  [round(r[2], 1) for r in rows],
        "max":  [round(r[3], 1) for r in rows],
    })


@app.get("/api/steps")
def api_steps(days: int = 30):
    from collections import defaultdict
    conn = get_conn()
    cur  = conn.cursor()
    cur.execute("""
        SELECT DATE(start_time AT TIME ZONE 'UTC') AS day,
               SUM(value)::bigint                  AS total_steps,
               source
        FROM biometric_timeseries
        WHERE type       = 'HKQuantityTypeIdentifierStepCount'
          AND start_time >= NOW() - INTERVAL '1 day' * %s
        GROUP BY 1, 3
        ORDER BY 1
    """, (days,))
    by_day: dict = defaultdict(int)
    for day, total, _ in cur.fetchall():
        by_day[str(day)] += int(total)
    cur.close()
    conn.close()
    days_out = sorted(by_day)
    return JSONResponse({"days": days_out, "steps": [by_day[d] for d in days_out]})


@app.get("/api/sleep")
def api_sleep(days: int = 30):
    from collections import defaultdict
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        WITH nights AS (
            SELECT DATE(start_time AT TIME ZONE 'UTC') AS night_date,
                   source, stage,
                   SUM(duration_min) AS dur_min
            FROM sleep_records
            WHERE start_time >= NOW() - INTERVAL '1 day' * %s
              AND stage NOT IN ('HKCategoryValueSleepAnalysisInBed')
            GROUP BY 1, 2, 3
        ),
        src_priority AS (
            SELECT DISTINCT ON (night_date) night_date, source
            FROM (
                SELECT night_date, source,
                       CASE source
                           WHEN 'whoop'       THEN 1
                           WHEN 'oura'        THEN 2
                           WHEN 'ultrahuman'  THEN 3
                           WHEN 'autosleep'   THEN 4
                           WHEN 'apple_watch' THEN 5
                           ELSE 6
                       END AS priority
                FROM nights
                GROUP BY night_date, source
            ) s
            ORDER BY night_date, priority
        )
        SELECT n.night_date, n.stage, n.dur_min
        FROM nights n JOIN src_priority p USING (night_date, source)
        ORDER BY night_date
    """, (days,))

    rows = cur.fetchall()
    cur.close()
    conn.close()

    def norm_stage(s):
        sl = (s or "").lower()
        if "deep"  in sl:                  return "deep"
        if "rem"   in sl:                  return "rem"
        if "core"  in sl:                  return "core"
        if "awake" in sl or "wake" in sl:  return "awake"
        return "other"

    nightly = defaultdict(lambda: defaultdict(float))
    for night_date, stage, dur_min in rows:
        nightly[jsonable(night_date)][norm_stage(stage)] += float(dur_min or 0)

    dates = sorted(nightly)
    return JSONResponse({
        "dates": dates,
        "deep":  [round(nightly[d].get("deep",  0), 1) for d in dates],
        "rem":   [round(nightly[d].get("rem",   0), 1) for d in dates],
        "core":  [round(nightly[d].get("core",  0), 1) for d in dates],
        "awake": [round(nightly[d].get("awake", 0), 1) for d in dates],
        "other": [round(nightly[d].get("other", 0), 1) for d in dates],
    })


@app.get("/api/workouts")
def api_workouts(days: int = 30):
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        SELECT date_trunc('week', start_time)::date AS week,
               SUM(duration_min)::float AS total_min,
               COUNT(*) AS sessions
        FROM workouts
        WHERE start_time >= NOW() - INTERVAL '1 day' * %s
        GROUP BY 1 ORDER BY 1
    """, (days,))
    weekly = [{"week": jsonable(r[0]), "total_min": round(r[1], 0), "sessions": r[2]}
              for r in cur.fetchall()]

    cur.execute("""
        SELECT start_time, activity_type,
               ROUND(duration_min, 0)::float,
               ROUND(energy_kcal,  0)::float,
               source
        FROM workouts ORDER BY start_time DESC LIMIT 10
    """)
    recent = [
        {
            "t":            jsonable(r[0]),
            "activity":     r[1],
            "duration_min": float(r[2]) if r[2] is not None else None,
            "kcal":         float(r[3]) if r[3] is not None else None,
            "source":       r[4],
        }
        for r in cur.fetchall()
    ]

    cur.close()
    conn.close()
    return JSONResponse({"weekly": weekly, "recent": recent})


@app.get("/api/weather")
def api_weather():
    import urllib.request as _ureq
    import json as _jlib
    try:
        req = _ureq.Request(
            "https://wttr.in/?format=j1",
            headers={"User-Agent": "Mozilla/5.0 health-hub/1.0"},
        )
        with _ureq.urlopen(req, timeout=6) as resp:
            data = _jlib.loads(resp.read())
        c    = data["current_condition"][0]
        area = (data.get("nearest_area") or [{}])[0]
        city = (
            (area.get("areaName")  or [{}])[0].get("value") or
            (area.get("region")    or [{}])[0].get("value") or ""
        )
        return JSONResponse({
            "temp_c":       int(c["temp_C"]),
            "feels_like_c": int(c["FeelsLikeC"]),
            "desc":         c["weatherDesc"][0]["value"],
            "city":         city,
        })
    except Exception as e:
        return JSONResponse({"temp_c": None, "desc": "", "city": "", "error": str(e)})


@app.get("/api/nutrition")
def api_nutrition(days: int = 30):
    conn = get_conn()
    cur = conn.cursor()

    cur.execute("""
        SELECT date, calories_kcal, expenditure_kcal, protein_g, fat_g, carbs_g
        FROM macrofactor_daily
        WHERE date >= CURRENT_DATE - INTERVAL '1 day' * %s
        ORDER BY date
    """, (days,))
    rows = cur.fetchall()
    cur.close()
    conn.close()

    def fv(v):
        return float(v) if v is not None else None

    return JSONResponse({
        "days":     [jsonable(r[0]) for r in rows],
        "calories": [fv(r[1]) for r in rows],
        "tdee":     [fv(r[2]) for r in rows],
        "protein":  [fv(r[3]) for r in rows],
        "fat":      [fv(r[4]) for r in rows],
        "carbs":    [fv(r[5]) for r in rows],
    })


TDEE_WINDOW_DAYS = 14
TDEE_MIN_READINGS = 7


@app.get("/api/tdee")
def api_tdee(days: int = 30):
    conn = get_conn()
    cur = conn.cursor()

    # Pull extra lookback so the trailing window for the earliest requested
    # date still has enough history behind it.
    lookback_days = days + TDEE_WINDOW_DAYS

    cur.execute("""
        SELECT DATE(recorded_at AT TIME ZONE 'Asia/Tokyo') AS day, AVG(weight_kg)
        FROM weight_logs
        WHERE recorded_at >= NOW() - INTERVAL '1 day' * %s
        GROUP BY 1
    """, (lookback_days,))
    daily_weights = {r[0]: float(r[1]) for r in cur.fetchall()}

    cur.execute("""
        SELECT DATE(logged_at AT TIME ZONE 'Asia/Tokyo') AS day, SUM(energy_kcal)
        FROM meal_logs
        WHERE logged_at >= NOW() - INTERVAL '1 day' * %s
          AND energy_kcal IS NOT NULL
        GROUP BY 1
    """, (lookback_days,))
    daily_intake = {r[0]: float(r[1]) for r in cur.fetchall()}

    cur.execute("""
        SELECT date, expenditure_kcal
        FROM macrofactor_daily
        WHERE date >= CURRENT_DATE - INTERVAL '1 day' * %s
        ORDER BY date
    """, (days,))
    macrofactor_rows = cur.fetchall()

    cur.close()
    conn.close()

    target_dates = [date.today() - timedelta(days=i) for i in range(days)][::-1]
    calculated = compute_tdee_series(
        daily_weights, daily_intake, target_dates,
        window_days=TDEE_WINDOW_DAYS, min_readings=TDEE_MIN_READINGS,
    )
    macrofactor_by_date = {r[0]: (float(r[1]) if r[1] is not None else None) for r in macrofactor_rows}

    return JSONResponse({
        "dates":            [jsonable(d) for d in target_dates],
        "calories":         [daily_intake.get(d) for d in target_dates],
        "calculated_tdee":  [calculated[d] for d in target_dates],
        "macrofactor_tdee": [macrofactor_by_date.get(d) for d in target_dates],
    })


# ─────────────────────────────────────────────────────────────────
# System / uptime endpoints
# ─────────────────────────────────────────────────────────────────

@app.get("/api/uptime")
def api_uptime():
    try:
        with open("/proc/uptime") as f:
            secs = float(f.read().split()[0])
        d = int(secs // 86400)
        h = int((secs % 86400) // 3600)
        m = int((secs % 3600) // 60)
        parts = []
        if d:
            parts.append(f"{d}d")
        parts.append(f"{h:02d}h {m:02d}m")
        return JSONResponse({"uptime": " ".join(parts), "seconds": secs})
    except Exception as e:
        return JSONResponse({"uptime": "—", "error": str(e)})


# ── Docker socket helpers ─────────────────────────────────────────

class _DockerConn(http.client.HTTPConnection):
    """HTTP connection via the Docker Unix socket."""
    def __init__(self):
        super().__init__("localhost")

    def connect(self):
        sock = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
        sock.connect("/var/run/docker.sock")
        self.sock = sock


def _docker_get(path: str):
    try:
        conn = _DockerConn()
        conn.request("GET", path, headers={"Host": "localhost"})
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return data, resp.status
    except Exception as e:
        return None, str(e)


def _decode_docker_log_stream(raw: bytes) -> list[str]:
    """Strip Docker multiplexed stream headers (8 bytes each) and return lines."""
    lines, i = [], 0
    while i + 8 <= len(raw):
        size = struct.unpack(">I", raw[i + 4: i + 8])[0]
        if i + 8 + size > len(raw):
            break
        chunk = raw[i + 8: i + 8 + size].decode("utf-8", errors="replace")
        for line in chunk.splitlines():
            line = line.strip()
            if line:
                lines.append(line)
        i += 8 + size
    return lines


_MEDIA_NAMES = re.compile(r"plex|sonarr|radarr|prowlarr", re.I)
_INTEREST    = re.compile(
    r"download|import|grab|error|fail|update|scan|session|stream|play|transcode|added|remov",
    re.I,
)
# Strip ANSI codes that some apps emit
_ANSI = re.compile(r"\x1b\[[0-9;]*m")


def _parse_docker_log_lines(lines: list[str], source: str) -> list[dict]:
    events = []
    for line in lines:
        ts, msg = "", _ANSI.sub("", line).strip()
        # Docker prefixes timestamps when ?timestamps=1: 2026-08-09T15:30:00.000000000Z …
        if len(msg) > 30 and msg[4] == "-" and msg[10] == "T":
            try:
                dt = datetime.fromisoformat(msg[:19])
                ts = dt.strftime("%m-%d %H:%M")
                msg = msg[31:].strip()
            except Exception:
                pass
        if _INTEREST.search(msg) and msg:
            events.append({"source": source, "time": ts, "message": msg[:140]})
    return events


@app.get("/api/sysactivity")
def api_sysactivity():
    # List running containers
    raw, status = _docker_get("/containers/json")
    if raw is None:
        return JSONResponse({"items": [], "error": "docker socket unavailable"})

    try:
        containers = __import__("json").loads(raw)
    except Exception:
        return JSONResponse({"items": [], "error": "could not parse container list"})

    all_events: list[dict] = []
    seen: set[str] = set()

    for c in containers:
        names   = " ".join(c.get("Names", []))
        image   = c.get("Image", "")
        label   = (names + " " + image).lower()
        m       = _MEDIA_NAMES.search(label)
        if not m:
            continue
        source = m.group(0).lower()
        if source in seen:
            continue
        seen.add(source)

        log_raw, _ = _docker_get(
            f"/containers/{c['Id']}/logs?stdout=1&stderr=1&tail=150&timestamps=1"
        )
        if log_raw:
            lines  = _decode_docker_log_stream(log_raw)
            events = _parse_docker_log_lines(lines, source)
            all_events.extend(events[-30:])

    # Sort by timestamp string descending (ISO-prefix sort works)
    all_events.sort(key=lambda e: e["time"], reverse=True)
    return JSONResponse({"items": all_events[:60], "sources": list(seen)})


# ─────────────────────────────────────────────────────────────────
# User-facing dashboard
# ─────────────────────────────────────────────────────────────────

_DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Health Hub</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3/dist/chartjs-adapter-date-fns.bundle.min.js"></script>
<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: 'Space Mono', monospace;
  background: #f5f3ec;
  color: #1a1a1a;
  padding: 1.5rem;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
  gap: .75rem;
}
h1 { font-size: 1.2rem; font-weight: 700; letter-spacing: .04em; }
h1 span { color: #888; font-size: .85rem; font-weight: 400; margin-left: .5rem; }
.range-btns button {
  background: transparent;
  color: #888;
  border: 1px solid #ccc9bf;
  padding: .3rem .8rem;
  cursor: pointer;
  margin-left: .4rem;
  font-family: 'Space Mono', monospace;
  font-size: .78rem;
  letter-spacing: .06em;
  transition: background .15s, color .15s;
}
.range-btns button.active,
.range-btns button:hover {
  background: #1a1a1a;
  color: #f5f3ec;
  border-color: #1a1a1a;
}
.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
  margin-bottom: 1.5rem;
}
@media (max-width: 900px) { .cards { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 500px) { .cards { grid-template-columns: 1fr; } }
.card {
  background: #ede9e1;
  border: 1px solid #ccc9bf;
  padding: 1rem 1.25rem;
}
.card-label { font-size: .68rem; color: #888; text-transform: uppercase; letter-spacing: .1em; margin-bottom: .5rem; }
.card-value { font-size: 1.9rem; font-weight: 700; color: #1a1a1a; line-height: 1; }
.card-sub   { font-size: .72rem; color: #aaa; margin-top: .35rem; }
.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
}
@media (max-width: 900px) { .charts-grid { grid-template-columns: 1fr; } }
.chart-box {
  background: #ede9e1;
  border: 1px solid #ccc9bf;
  padding: 1.25rem;
}
.chart-box h2 {
  font-size: .72rem;
  color: #888;
  margin-bottom: 1rem;
  font-weight: 400;
  text-transform: uppercase;
  letter-spacing: .1em;
}
.chart-box canvas { max-height: 200px; }
.info-icon {
  cursor: help;
  font-size: .85rem;
  opacity: .6;
  text-transform: none;
  letter-spacing: normal;
}
.info-icon:hover { opacity: 1; }
table.recent {
  width: 100%;
  border-collapse: collapse;
  font-size: .72rem;
  margin-top: 1rem;
}
table.recent th {
  color: #888;
  text-align: left;
  padding: .3rem .4rem;
  border-bottom: 1px solid #ccc9bf;
  font-weight: 400;
  letter-spacing: .05em;
}
table.recent td {
  padding: .3rem .4rem;
  border-bottom: 1px solid #dedad2;
  color: #444;
}
</style>
</head>
<body>

<header>
  <h1>Health Hub <span>dashboard</span></h1>
  <div class="range-btns">
    <button data-d="7">7d</button>
    <button data-d="30" class="active">30d</button>
    <button data-d="90">90d</button>
  </div>
</header>

<div class="cards">
  <div class="card">
    <div class="card-label">Weight</div>
    <div class="card-value" id="snap-weight">—</div>
    <div class="card-sub">kg &middot; latest reading</div>
  </div>
  <div class="card">
    <div class="card-label">Calories</div>
    <div class="card-value" id="snap-cals">—</div>
    <div class="card-sub" id="snap-tdee">kcal eaten</div>
  </div>
  <div class="card">
    <div class="card-label">Sleep last night</div>
    <div class="card-value" id="snap-sleep">—</div>
    <div class="card-sub">hours</div>
  </div>
  <div class="card">
    <div class="card-label">Steps</div>
    <div class="card-value" id="snap-steps">—</div>
    <div class="card-sub">most recent day</div>
  </div>
</div>

<div class="charts-grid">
  <div class="chart-box">
    <h2>Weight</h2>
    <canvas id="chartWeight"></canvas>
  </div>
  <div class="chart-box">
    <h2>Recovery &mdash; RHR &amp; HRV</h2>
    <canvas id="chartRecovery"></canvas>
  </div>
  <div class="chart-box">
    <h2>Sleep Stages</h2>
    <canvas id="chartSleep"></canvas>
  </div>
  <div class="chart-box">
    <h2>Workout Volume (weekly)</h2>
    <canvas id="chartWorkouts"></canvas>
    <table class="recent" id="recentWorkouts">
      <thead><tr>
        <th>Date</th><th>Activity</th><th>Min</th><th>kcal</th><th>Source</th>
      </tr></thead>
      <tbody></tbody>
    </table>
  </div>
  <div class="chart-box">
    <h2>Calories vs TDEE <span class="info-icon" title="Add comparison to macrofactor later">&#128172;</span></h2>
    <canvas id="chartNutrition"></canvas>
  </div>
  <div class="chart-box">
    <h2>Macros (kcal)</h2>
    <canvas id="chartMacros"></canvas>
  </div>
  <div class="chart-box">
    <h2>Heart Rate (daily min / avg / max)</h2>
    <canvas id="chartHR"></canvas>
  </div>
  <div class="chart-box">
    <h2>Steps (daily)</h2>
    <canvas id="chartSteps"></canvas>
  </div>
</div>

<script>
// ── chart defaults ──────────────────────────────────────────────
const GRID  = 'rgba(0,0,0,0.07)';
const TICK  = '#888';
const LEG   = '#555';
Chart.defaults.color = TICK;
Chart.defaults.borderColor = GRID;
Chart.defaults.font.family = "'Space Mono', monospace";
Chart.defaults.font.size = 11;

const _charts = {};
function mkChart(id, config) {
  if (_charts[id]) _charts[id].destroy();
  _charts[id] = new Chart(document.getElementById(id), config);
}

function xTime() {
  return {
    type: 'time',
    time: { tooltipFormat: 'MMM d', displayFormats: { day: 'MMM d', week: 'MMM d' } },
    grid: { color: GRID },
    ticks: { color: TICK, maxTicksLimit: 8 },
  };
}
function yLeft(label) {
  return { grid: { color: GRID }, ticks: { color: TICK }, title: { display: !!label, text: label, color: '#555' } };
}
function yRight(label) {
  return { position: 'right', grid: { drawOnChartArea: false }, ticks: { color: TICK },
           title: { display: !!label, text: label, color: '#555' } };
}

// ── snapshot ────────────────────────────────────────────────────
async function loadSnapshot() {
  const s = await fetch('/api/snapshot').then(r => r.json());
  document.getElementById('snap-weight').textContent =
    s.weight_kg != null ? s.weight_kg.toFixed(1) : '—';
  document.getElementById('snap-cals').textContent =
    s.calories_kcal != null ? Math.round(s.calories_kcal).toLocaleString() : '—';
  if (s.tdee_kcal != null)
    document.getElementById('snap-tdee').textContent =
      `kcal eaten · TDEE ${Math.round(s.tdee_kcal).toLocaleString()}`;
  document.getElementById('snap-sleep').textContent =
    s.sleep_min != null ? (s.sleep_min / 60).toFixed(1) : '—';
  document.getElementById('snap-steps').textContent =
    s.steps != null ? s.steps.toLocaleString() : '—';
}

function srcLabel(src) { return src; }

// ── weight ──────────────────────────────────────────────────────
function buildWeight(d) {
  const SRC_COLOR = {
    'Smart Scale': '#0288d1', 'WHOOP': '#c2185b', 'Apple Watch': '#2e7d32',
    'COROS': '#e65100', 'iPhone': '#6a1b9a', 'Health': '#00695c',
    'Oura': '#e67e22', 'Garmin': '#005b99', 'Polar': '#cc0000',
  };
  const bySrc = {};
  for (const r of d.readings) {
    (bySrc[r.source] = bySrc[r.source] || []).push({ x: r.t, y: r.v });
  }
  const datasets = Object.entries(bySrc).map(([src, pts]) => ({
    label: srcLabel(src), type: 'scatter', data: pts,
    backgroundColor: SRC_COLOR[src] || '#aaa', pointRadius: 3, pointHoverRadius: 5,
  }));
  if (d.trend.length) {
    datasets.push({
      label: 'MacroFactor Trend', type: 'line',
      data: d.trend.map(r => ({ x: r.t, y: r.v })),
      borderColor: '#1a1a1a', borderWidth: 1.5, pointRadius: 0, fill: false, tension: 0.3,
    });
  }
  mkChart('chartWeight', {
    type: 'scatter',
    data: { datasets },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: { x: xTime(), y: yLeft('kg') },
    },
  });
}

// ── recovery ─────────────────────────────────────────────────────
function buildRecovery(d) {
  mkChart('chartRecovery', {
    type: 'line',
    data: {
      datasets: [
        {
          label: 'RHR bpm', yAxisID: 'yR',
          data: d.rhr.map(r => ({ x: r.t, y: r.v })),
          borderColor: '#c62828', backgroundColor: 'rgba(198,40,40,0.06)',
          pointRadius: 2, spanGaps: false, tension: 0.2,
        },
        {
          label: 'HRV ms', yAxisID: 'yH',
          data: d.hrv.map(r => ({ x: r.t, y: r.v })),
          borderColor: '#1565c0', backgroundColor: 'rgba(21,101,192,0.06)',
          pointRadius: 2, spanGaps: false, tension: 0.2,
        },
      ],
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: { x: xTime(), yR: yLeft('RHR bpm'), yH: yRight('HRV ms') },
    },
  });
}

// ── sleep ────────────────────────────────────────────────────────
function buildSleep(d) {
  mkChart('chartSleep', {
    type: 'bar',
    data: {
      labels: d.dates,
      datasets: [
        { label: 'Deep',  data: d.deep,  backgroundColor: '#1a237e', stack: 's' },
        { label: 'REM',   data: d.rem,   backgroundColor: '#4a148c', stack: 's' },
        { label: 'Core',  data: d.core,  backgroundColor: '#1565c0', stack: 's' },
        { label: 'Awake', data: d.awake, backgroundColor: '#b71c1c', stack: 's' },
        { label: 'Other', data: d.other, backgroundColor: '#888',    stack: 's' },
      ],
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: {
        x: { stacked: true, grid: { color: GRID }, ticks: { color: TICK, maxTicksLimit: 10 } },
        y: { stacked: true, ...yLeft('min') },
      },
    },
  });
}

// ── workouts ─────────────────────────────────────────────────────
function buildWorkouts(d) {
  mkChart('chartWorkouts', {
    type: 'bar',
    data: {
      labels: d.weekly.map(r => r.week),
      datasets: [{ label: 'Duration (min)', data: d.weekly.map(r => r.total_min), backgroundColor: '#2e7d32' }],
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: { x: { grid: { color: GRID }, ticks: { color: TICK } }, y: yLeft('min') },
    },
  });
  const tbody = document.querySelector('#recentWorkouts tbody');
  tbody.innerHTML = d.recent.map(r => {
    const dt = r.t ? r.t.replace('T', ' ').slice(0, 16) : '—';
    return `<tr>
      <td>${dt}</td>
      <td>${r.activity || '—'}</td>
      <td>${r.duration_min != null ? r.duration_min : '—'}</td>
      <td>${r.kcal != null ? r.kcal : '—'}</td>
      <td>${r.source ? srcLabel(r.source) : '—'}</td>
    </tr>`;
  }).join('');
}

// ── nutrition ─────────────────────────────────────────────────────
function buildNutrition(d) {
  mkChart('chartNutrition', {
    type: 'line',
    data: {
      labels: d.dates,
      datasets: [
        {
          label: 'Calories',
          data: d.calories,
          borderColor: '#e65100', backgroundColor: 'rgba(230,81,0,0.06)',
          pointRadius: 2, tension: 0.2,
        },
        {
          label: 'TDEE',
          data: d.calculated_tdee,
          borderColor: '#888', borderDash: [4, 3],
          backgroundColor: 'transparent', pointRadius: 0, tension: 0.2,
        },
      ],
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: {
        x: { grid: { color: GRID }, ticks: { color: TICK, maxTicksLimit: 8 } },
        y: yLeft('kcal'),
      },
    },
  });
}

// ── macros ────────────────────────────────────────────────────────
function buildMacros(d) {
  mkChart('chartMacros', {
    type: 'bar',
    data: {
      labels: d.days,
      datasets: [
        { label: 'Protein', data: d.protein.map(v => v != null ? Math.round(v * 4) : null), backgroundColor: '#1565c0', stack: 'm' },
        { label: 'Fat',     data: d.fat.map(v     => v != null ? Math.round(v * 9) : null), backgroundColor: '#e65100', stack: 'm' },
        { label: 'Carbs',   data: d.carbs.map(v   => v != null ? Math.round(v * 4) : null), backgroundColor: '#2e7d32', stack: 'm' },
      ],
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: {
        x: { stacked: true, grid: { color: GRID }, ticks: { color: TICK, maxTicksLimit: 8 } },
        y: { stacked: true, ...yLeft('kcal') },
      },
    },
  });
}

// ── heart rate ────────────────────────────────────────────────────
function buildHR(d) {
  mkChart('chartHR', {
    type: 'line',
    data: {
      labels: d.days,
      datasets: [
        {
          label: 'Max', data: d.max,
          borderColor: 'rgba(198,40,40,0.35)', backgroundColor: 'rgba(198,40,40,0.08)',
          pointRadius: 0, fill: '+1', tension: 0.3, borderWidth: 1,
        },
        {
          label: 'Avg', data: d.avg,
          borderColor: '#c62828', backgroundColor: 'transparent',
          pointRadius: 2, tension: 0.3, borderWidth: 1.5,
        },
        {
          label: 'Min', data: d.min,
          borderColor: 'rgba(198,40,40,0.35)', backgroundColor: 'rgba(198,40,40,0.08)',
          pointRadius: 0, fill: '-1', tension: 0.3, borderWidth: 1,
        },
      ],
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: LEG, boxWidth: 10 } } },
      scales: {
        x: { grid: { color: GRID }, ticks: { color: TICK, maxTicksLimit: 10 } },
        y: { ...yLeft('bpm'), beginAtZero: false },
      },
    },
  });
}

// ── steps ─────────────────────────────────────────────────────────
function buildSteps(d) {
  mkChart('chartSteps', {
    type: 'bar',
    data: {
      labels: d.days,
      datasets: [{ label: 'Steps', data: d.steps, backgroundColor: '#00695c' }],
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: GRID }, ticks: { color: TICK, maxTicksLimit: 10 } },
        y: { ...yLeft('steps'), beginAtZero: true },
      },
    },
  });
}

// ── load all ──────────────────────────────────────────────────────
async function loadAll(days) {
  const [weight, recovery, sleep, workouts, nutrition, steps, hr, tdee] = await Promise.all([
    fetch(`/api/weight?days=${days}`).then(r => r.json()),
    fetch(`/api/recovery?days=${days}`).then(r => r.json()),
    fetch(`/api/sleep?days=${days}`).then(r => r.json()),
    fetch(`/api/workouts?days=${days}`).then(r => r.json()),
    fetch(`/api/nutrition?days=${days}`).then(r => r.json()),
    fetch(`/api/steps?days=${days}`).then(r => r.json()),
    fetch(`/api/heartrate?days=${days}`).then(r => r.json()),
    fetch(`/api/tdee?days=${days}`).then(r => r.json()),
  ]);
  buildWeight(weight);
  buildRecovery(recovery);
  buildSleep(sleep);
  buildWorkouts(workouts);
  buildNutrition(tdee);
  buildMacros(nutrition);
  buildSteps(steps);
  buildHR(hr);
}

document.querySelectorAll('.range-btns button').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.range-btns button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    loadAll(parseInt(btn.dataset.d, 10));
  });
});

loadSnapshot();
loadAll(30);
</script>
</body>
</html>"""


@app.get("/health", response_class=HTMLResponse)
def health_dashboard():
    return HTMLResponse(_DASHBOARD_HTML)


_HOME_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title></title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html, body {
  width: 100%; height: 100%;
  background: #f5f3ec;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
body { opacity: 0; animation: fi .7s ease .1s forwards; }
@keyframes fi { to { opacity: 1; } }
#hub {
  /* fill the smaller of vw/vh, cap at 700×600, maintain 7:6 ratio */
  width:  min(90vmin, 700px);
  height: min(77.14vmin, 600px);
}
.seg {
  cursor: pointer;
  transform-origin: 350px 300px;
  transform: scale(0.5);
  opacity: 0;
  transition: opacity .25s ease, transform .32s cubic-bezier(.34,1.4,.64,1);
}
.seg.on { opacity: 1; transform: scale(1); }
.sf, .ssf {
  fill: transparent;
  stroke: #1a1a1a;
  stroke-width: 1.5;
  transition: fill .15s;
}
.seg:hover .sf,
.seg:hover .ssf { fill: rgba(26,26,26,0.07); }
.sl {
  fill: #1a1a1a;
  font-family: 'Space Mono', monospace;
  font-size: 12px;
  letter-spacing: .08em;
  text-anchor: middle;
  dominant-baseline: middle;
  pointer-events: none;
}
#cb {
  fill: transparent;
  stroke: #1a1a1a;
  stroke-width: 1.5;
  cursor: pointer;
  transition: stroke .2s;
}
#cb:hover { stroke: #555; }
.ct {
  font-family: 'Space Mono', monospace;
  text-anchor: middle;
  dominant-baseline: middle;
  fill: #1a1a1a;
}
.ct.dim   { fill: #888; }
.ct.faint { fill: #c0bcb2; }
line.hr { stroke: #ccc9bf; stroke-width: .8; }
/* ── corner overlays ────────────────────────────────── */
#cal-panel {
  position: fixed;
  bottom: 1.5rem;
  left: 1.5rem;
  width: 155px;
  opacity: 0.2;
  transition: opacity .25s;
  cursor: default;
}
#cal-panel:hover { opacity: 1; }
#uptime {
  position: fixed;
  bottom: 1.5rem;
  left: 50%;
  transform: translateX(-50%);
  font-family: 'Space Mono', monospace;
  font-size: 9px;
  letter-spacing: .14em;
  color: #888;
  white-space: nowrap;
}
#sys-panel {
  position: fixed;
  bottom: 1.5rem;
  right: 1.5rem;
  width: 200px;
  opacity: 0.2;
  transition: opacity .25s;
  cursor: default;
}
#sys-panel:hover { opacity: 1; }
/* ── panel header ────────────────────────────────────── */
.panel-hd {
  font-family: 'Space Mono', monospace;
  font-size: 8px;
  letter-spacing: .14em;
  text-transform: uppercase;
  color: #888;
  border-bottom: 1px solid #ccc9bf;
  padding-bottom: 6px;
  margin-bottom: 8px;
}
/* ── calendar ────────────────────────────────────────── */
.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
}
.cal-hd {
  font-family: 'Space Mono', monospace;
  font-size: 8px;
  color: #888;
  padding-bottom: 5px;
}
.cal-day {
  font-family: 'Space Mono', monospace;
  font-size: 9px;
  color: #1a1a1a;
  padding: 3px 0;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cal-day.today {
  background: #1a1a1a;
  color: #f5f3ec;
}
/* ── system panel ────────────────────────────────────── */
.sys-item {
  padding: 4px 0;
  border-bottom: 1px solid #e8e5dd;
  overflow: hidden;
}
.sys-src {
  font-family: 'Space Mono', monospace;
  font-size: 7px;
  color: #888;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.sys-msg {
  font-family: 'Space Mono', monospace;
  font-size: 8px;
  color: #1a1a1a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
#sys-panel:hover .sys-msg { white-space: normal; }
.sys-no { font-family: 'Space Mono', monospace; font-size: 8px; color: #888; }
#sys-list {
  max-height: 130px;
  overflow: hidden;
  transition: max-height .35s ease;
}
#sys-panel:hover #sys-list {
  max-height: calc(100vh - 3rem - 28px);
  overflow-y: auto;
}
.sys-time {
  font-family: 'Space Mono', monospace;
  font-size: 7px;
  color: #888;
  margin-top: 1px;
}
</style>
</head>
<body>
<div id="cal-panel">
  <div class="panel-hd" id="cal-title"></div>
  <div class="cal-grid" id="cal-grid"></div>
</div>
<div id="uptime">—</div>
<div id="sys-panel">
  <div class="panel-hd">system</div>
  <div id="sys-list"></div>
</div>
<svg id="hub" viewBox="0 0 700 600" xmlns="http://www.w3.org/2000/svg">
<defs>
  <path id="tp-health"/><path id="tp-ent"/><path id="tp-mem"/>
  <path id="tp-tv"/><path id="tp-mov"/>
  <path id="tp-plex-1"/><path id="tp-plex-2"/>
</defs>

  <!-- backdrop: click to close -->
  <rect id="bd" x="0" y="0" width="700" height="600"
        fill="transparent" style="display:none;cursor:default"/>

  <!-- main segments -->
  <g class="seg" id="s-health">
    <path class="sf" id="p-health"/>
    <text class="sl"><textPath href="#tp-health" startOffset="50%" text-anchor="middle" alignment-baseline="middle">HEALTH</textPath></text>
  </g>
  <g class="seg" id="s-ent">
    <path class="sf" id="p-ent"/>
    <text class="sl"><textPath href="#tp-ent" startOffset="50%" text-anchor="middle" alignment-baseline="middle">ENTERTAINMENT</textPath></text>
  </g>
  <g class="seg" id="s-mem">
    <path class="sf" id="p-mem"/>
    <text class="sl"><textPath href="#tp-mem" startOffset="50%" text-anchor="middle" alignment-baseline="middle">MEMORY</textPath></text>
  </g>

  <!-- entertainment sub-segments -->
  <g class="seg" id="s-tv">
    <path class="ssf" id="p-tv"/>
    <text class="sl"><textPath href="#tp-tv" startOffset="50%" text-anchor="middle" alignment-baseline="middle">TV SHOWS</textPath></text>
  </g>
  <g class="seg" id="s-mov">
    <path class="ssf" id="p-mov"/>
    <text class="sl"><textPath href="#tp-mov" startOffset="50%" text-anchor="middle" alignment-baseline="middle">MOVIES</textPath></text>
  </g>
  <g class="seg" id="s-plex">
    <path class="ssf" id="p-plex"/>
    <text class="sl"><textPath href="#tp-plex-1" startOffset="50%" text-anchor="middle" alignment-baseline="middle">MEDIA</textPath></text>
    <text class="sl"><textPath href="#tp-plex-2" startOffset="50%" text-anchor="middle" alignment-baseline="middle">LIBRARY</textPath></text>
  </g>

  <!-- circle -->
  <circle id="cb" cx="350" cy="300" r="130"/>

  <!-- content nodes — all y positions and visibility set by JS layout engine -->
  <text class="ct"       id="c-greet" x="350" font-size="14" display="none"></text>
  <line class="hr"       id="hr1"                             display="none"/>
  <text class="ct"       id="c-day"   x="350" font-size="12" display="none"></text>
  <text class="ct dim"   id="c-date"  x="350" font-size="10" display="none"></text>
  <line class="hr"       id="hr2"                             display="none"/>
  <text class="ct"       id="c-wthr"  x="350" font-size="11" display="none"></text>
  <text class="ct dim"   id="c-city"  x="350" font-size="10" display="none"></text>
  <line class="hr"       id="hr3"                             display="none"/>
  <text class="ct faint" id="c-since" x="350" font-size="8"
        letter-spacing=".1em"                                 display="none">since birth</text>
  <text class="ct"       id="c-yd"    x="350" font-size="12" display="none"></text>
  <text class="ct dim"   id="c-hms"   x="350" font-size="11" display="none"></text>

</svg>
<script>
// ── config ────────────────────────────────────────────
const URLS = {
  health : '/health',
  memory : 'http://localhost:2283',
  tv     : 'http://localhost:8989',
  movies : 'http://localhost:7878',
  plex   : 'http://127.0.0.1:32400/web/index.html#!',
};
const CX=350, CY=300, CR=130;

// ── segment geometry ──────────────────────────────────
function pol(r,deg){ const a=(deg-90)*Math.PI/180; return [CX+r*Math.cos(a), CY+r*Math.sin(a)]; }
function arcD(r1,r2,s,e){
  const [x1,y1]=pol(r1,s),[x2,y2]=pol(r1,e),[x3,y3]=pol(r2,e),[x4,y4]=pol(r2,s);
  const lg=(e-s)>180?1:0;
  return `M${x1} ${y1} A${r1} ${r1} 0 ${lg} 1 ${x2} ${y2} L${x3} ${y3} A${r2} ${r2} 0 ${lg} 0 ${x4} ${y4}Z`;
}
// single open arc used for textPath labels
// rev=true reverses the path direction so text reads in the opposite direction
function arcPath(r,s,e,rev=false){
  const [x1,y1]=rev?pol(r,e):pol(r,s), [x2,y2]=rev?pol(r,s):pol(r,e);
  const span=((e-s)%360+360)%360, lg=span>180?1:0, sf=rev?0:1;
  return `M${x1.toFixed(2)} ${y1.toFixed(2)} A${r} ${r} 0 ${lg} ${sf} ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

const R1=CR, R2=R1+40, RS1=R2+1, RS2=RS1+40;  // both rings 40px thick
const RM=(R1+R2)/2, RSM=(RS1+RS2)/2;

// segment shapes
document.getElementById('p-health').setAttribute('d', arcD(R1, R2, -60,  60));
document.getElementById('p-ent').setAttribute('d',    arcD(R1, R2,  60, 180));
document.getElementById('p-mem').setAttribute('d',    arcD(R1, R2, 180, 300));
document.getElementById('p-tv').setAttribute('d',   arcD(RS1, RS2,  60, 100));
document.getElementById('p-mov').setAttribute('d',  arcD(RS1, RS2, 100, 140));
document.getElementById('p-plex').setAttribute('d', arcD(RS1, RS2, 140, 180));

// text arcs — labels follow the curvature of each segment
document.getElementById('tp-health').setAttribute('d', arcPath(RM,   -60,  60));
document.getElementById('tp-ent').setAttribute('d',    arcPath(RM,    60, 180, true));  // reversed → reads upward on right
document.getElementById('tp-mem').setAttribute('d',    arcPath(RM,   180, 300, true));  // reversed → reads downward on left
document.getElementById('tp-tv').setAttribute('d',     arcPath(RSM,    60, 100, true));
document.getElementById('tp-mov').setAttribute('d',    arcPath(RSM,   100, 140, true));
document.getElementById('tp-plex-1').setAttribute('d', arcPath(RSM-8, 140, 180, true));
document.getElementById('tp-plex-2').setAttribute('d', arcPath(RSM+8, 140, 180, true));

// ── layout engine ─────────────────────────────────────
// item: { type:'text'|'hr', id, fs? }
// gap rules between consecutive items
const G = { TT:5, TH:7, HT:7 };
function gap(a,b){ return a.type==='text'&&b.type==='text'?G.TT : a.type==='text'?G.TH : G.HT; }

function blockH(items){
  let h=0;
  for(let i=0;i<items.length;i++){
    if(items[i].type==='text') h+=items[i].fs;
    if(i<items.length-1) h+=gap(items[i],items[i+1]);
  }
  return h;
}

function chordHW(y){ const dy=Math.abs(y-CY); return dy>=CR?0:Math.sqrt(CR*CR-dy*dy); }

function applyLayout(items){
  const h=blockH(items);
  let y=CY - h/2;          // top of first item's slot
  for(let i=0;i<items.length;i++){
    const cur=items[i], el=document.getElementById(cur.id);
    if(cur.type==='text'){
      el.setAttribute('y',(y+cur.fs/2).toFixed(1)); // dominant-baseline:middle → y is glyph centre
      el.removeAttribute('display');
      y+=cur.fs;
    } else {
      const hw=chordHW(y)-10;                        // line hugs the chord with a small inset
      el.setAttribute('x1',(CX-hw).toFixed(1)); el.setAttribute('x2',(CX+hw).toFixed(1));
      el.setAttribute('y1',y.toFixed(1));        el.setAttribute('y2',y.toFixed(1));
      el.removeAttribute('display');
    }
    if(i<items.length-1) y+=gap(cur,items[i+1]);
  }
}

const FULL = [
  {type:'text',id:'c-greet',fs:14},
  {type:'hr',  id:'hr1'},
  {type:'text',id:'c-day',  fs:12},
  {type:'text',id:'c-date', fs:10},
  {type:'hr',  id:'hr2'},
  {type:'text',id:'c-wthr', fs:11},
  {type:'text',id:'c-city', fs:10},
  {type:'hr',  id:'hr3'},
  {type:'text',id:'c-since',fs:8},
  {type:'text',id:'c-yd',   fs:12},
  {type:'text',id:'c-hms',  fs:11},
];
const COMPACT = [
  {type:'text',id:'c-greet',fs:14},
  {type:'hr',  id:'hr3'},
  {type:'text',id:'c-since',fs:8},
  {type:'text',id:'c-yd',   fs:12},
  {type:'text',id:'c-hms',  fs:11},
];
const ALL_IDS = ['c-greet','c-day','c-date','c-wthr','c-city','c-since','c-yd','c-hms','hr1','hr2','hr3'];

function initLayout(){
  ALL_IDS.forEach(id => document.getElementById(id).setAttribute('display','none'));
  const w = document.getElementById('hub').getBoundingClientRect().width;
  applyLayout(w >= 350 ? FULL : COMPACT);
}

// ── menu ──────────────────────────────────────────────
let open=false, entOpen=false;
const on  = id => document.getElementById(id).classList.add('on');
const off = id => document.getElementById(id).classList.remove('on');

function openMenu(){ open=true; document.getElementById('bd').style.display='block'; ['s-health','s-ent','s-mem'].forEach((id,i)=>setTimeout(()=>on(id),i*65)); }
function closeMenu(){ open=false; entOpen=false; document.getElementById('bd').style.display='none'; ['s-health','s-ent','s-mem','s-tv','s-mov','s-plex'].forEach(off); }

document.getElementById('cb').addEventListener('click',e=>{ e.stopPropagation(); open?closeMenu():openMenu(); });
document.getElementById('s-health').addEventListener('click',e=>{ e.stopPropagation(); window.location.href=URLS.health; });
document.getElementById('s-mem').addEventListener('click',e=>{ e.stopPropagation(); window.location.href=URLS.memory; });
document.getElementById('s-ent').addEventListener('click',e=>{ e.stopPropagation(); if(entOpen){off('s-tv');off('s-mov');off('s-plex');entOpen=false;}else{on('s-tv');setTimeout(()=>on('s-mov'),75);setTimeout(()=>on('s-plex'),150);entOpen=true;} });
document.getElementById('s-tv').addEventListener('click',e=>{ e.stopPropagation(); window.location.href=URLS.tv; });
document.getElementById('s-mov').addEventListener('click',e=>{ e.stopPropagation(); window.location.href=URLS.movies; });
document.getElementById('s-plex').addEventListener('click',e=>{ e.stopPropagation(); window.location.href=URLS.plex; });
document.getElementById('bd').addEventListener('click',closeMenu);

// ── clock & counter ───────────────────────────────────
const BIRTH=new Date(1994,6,4,23,10,0);
const WD=['sunday','monday','tuesday','wednesday','thursday','friday','saturday'];
const MN=['january','february','march','april','may','june','july','august','september','october','november','december'];
function greet(h){ return h>=5&&h<12?'good morning':h>=12&&h<17?'good afternoon':h>=17&&h<21?'good evening':'good night'; }
function pad(n){ return String(n).padStart(2,'0'); }

function tick(){
  const now=new Date();
  document.getElementById('c-greet').textContent=greet(now.getHours());
  document.getElementById('c-day').textContent=WD[now.getDay()];
  document.getElementById('c-date').textContent=`${now.getDate()} ${MN[now.getMonth()]} ${now.getFullYear()}`;
  const diff=now-BIRTH, s=Math.floor(diff/1000), m=Math.floor(s/60), h=Math.floor(m/60), d=Math.floor(h/24);
  const yrs=Math.floor(d/365.2425), rDays=Math.floor(d-yrs*365.2425);
  document.getElementById('c-yd').textContent=`${yrs} yrs  ${rDays} days`;
  document.getElementById('c-hms').textContent=`${pad(h%24)} : ${pad(m%60)} : ${pad(s%60)}`;
}
setInterval(tick,1000); tick();

// ── weather ───────────────────────────────────────────
fetch('/api/weather').then(r=>r.json()).then(w=>{
  if(w.temp_c!=null) document.getElementById('c-wthr').textContent=`${w.temp_c}°c  ${(w.desc||'').toLowerCase()}`;
  if(w.city)         document.getElementById('c-city').textContent=w.city.toLowerCase();
}).catch(()=>{});

// ── init ──────────────────────────────────────────────
initLayout();
window.addEventListener('resize', initLayout);

// ── calendar ──────────────────────────────────────────
function buildCalendar(){
  const now=new Date(), y=now.getFullYear(), mo=now.getMonth(), td=now.getDate();
  const MO=['january','february','march','april','may','june','july','august','september','october','november','december'];
  document.getElementById('cal-title').textContent=`${MO[mo]} ${y}`;
  const first=(new Date(y,mo,1).getDay()+6)%7; // Mon-first: 0=Mon…6=Sun
  const total=new Date(y,mo+1,0).getDate();
  const g=document.getElementById('cal-grid');
  g.innerHTML='';
  ['m','t','w','t','f','s','s'].forEach(d=>{const e=document.createElement('div');e.className='cal-hd';e.textContent=d;g.appendChild(e);});
  for(let i=0;i<first;i++){const e=document.createElement('div');e.className='cal-day';g.appendChild(e);}
  for(let d=1;d<=total;d++){const e=document.createElement('div');e.className='cal-day'+(d===td?' today':'');e.textContent=d;g.appendChild(e);}
}
buildCalendar();

// ── uptime ────────────────────────────────────────────
function refreshUptime(){
  fetch('/api/uptime').then(r=>r.json()).then(d=>{
    document.getElementById('uptime').textContent=d.uptime||'—';
  }).catch(()=>{});
}
refreshUptime();
setInterval(refreshUptime, 60000);

// ── sysactivity ───────────────────────────────────────
function sysHTML(items){
  if(!items||!items.length) return '<div class="sys-no">no recent activity</div>';
  return items.map(it=>{
    const t=it.time?`<div class="sys-time">${it.time}</div>`:'';
    return `<div class="sys-item"><div class="sys-src">${it.source||''}</div><div class="sys-msg">${it.message||''}</div>${t}</div>`;
  }).join('');
}
function loadSysActivity(){
  fetch('/api/sysactivity').then(r=>r.json()).then(d=>{
    document.getElementById('sys-list').innerHTML=sysHTML(d.items);
  }).catch(()=>{});
}
loadSysActivity();
setInterval(loadSysActivity, 30000);
</script>
</body>
</html>"""


@app.get("/", response_class=HTMLResponse)
def home():
    return HTMLResponse(_HOME_HTML)
