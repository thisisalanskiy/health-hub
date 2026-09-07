import os
import uuid as _uuid
from datetime import date, datetime, timedelta, timezone

import psycopg2
import psycopg2.extras
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

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


@app.get("/", response_class=HTMLResponse)
def home():
    return HTMLResponse(_DASHBOARD_HTML)


@app.get("/health", response_class=HTMLResponse)
def health_dashboard():
    """Back-compat alias for the dashboard (kept in case it's bookmarked)."""
    return RedirectResponse(url="/")
