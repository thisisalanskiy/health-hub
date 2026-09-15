"""One-time HRV archival export.

HRV has no live Health Connect source -- it's frozen historical data that
was imported from Apple Health / WHOOP once, long ago. Rather than build
app infrastructure to keep syncing/displaying it, this is a one-off script:
run it manually against the backend's Postgres to dump the full HRV history
to a JSON file for safekeeping.

This is purely an archival backup. Nothing in the app reads the output file
-- there is no import path.

Usage:
    python export_hrv_history.py <output_path.json>

Reuses the same DB connection convention (DATABASE_URL env var,
psycopg2.connect) and the same per-day AVG(value) grouping query as
/api/recovery in api/main.py.
"""
import json
import os
import sys

import psycopg2

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql://hcuser:hcpassword@localhost:5432/health_dashboard",
)

HRV_TYPE = "HKQuantityTypeIdentifierHeartRateVariabilitySDNN"


def fetch_hrv_history():
    conn = psycopg2.connect(DATABASE_URL)
    cur = conn.cursor()
    cur.execute("""
        SELECT date_trunc('day', start_time)::date AS day,
               AVG(value)::float
        FROM biometric_timeseries
        WHERE type = %s
        GROUP BY 1
        ORDER BY 1
    """, (HRV_TYPE,))
    rows = cur.fetchall()
    cur.close()
    conn.close()
    return [{"date": day.isoformat(), "hrv": round(val, 1)} for day, val in rows]


def main():
    if len(sys.argv) != 2:
        print("Usage: python export_hrv_history.py <output_path.json>", file=sys.stderr)
        sys.exit(1)

    output_path = sys.argv[1]
    records = fetch_hrv_history()

    with open(output_path, "w") as f:
        json.dump(records, f, indent=2)

    if records:
        print(
            f"Exported {len(records)} daily HRV records "
            f"({records[0]['date']} to {records[-1]['date']}) to {output_path}"
        )
    else:
        print(f"No HRV records found. Wrote empty archive to {output_path}")


if __name__ == "__main__":
    main()
