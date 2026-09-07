import psycopg2
from fastapi.testclient import TestClient

import main


def test_last_sync_matches_independently_computed_max():
    client = TestClient(main.app)

    resp = client.get("/api/last-sync")
    assert resp.status_code == 200
    body = resp.json()
    assert "latest" in body

    conn = psycopg2.connect(main.DATABASE_URL)
    cur = conn.cursor()
    cur.execute("""
        SELECT GREATEST(
            (SELECT MAX(recorded_at) FROM weight_logs),
            (SELECT MAX(logged_at) FROM meal_logs),
            (SELECT MAX(start_time) FROM workouts),
            (SELECT MAX(start_time) FROM sleep_records),
            (SELECT MAX(start_time) FROM biometric_timeseries)
        )
    """)
    expected = cur.fetchone()[0]
    cur.close()
    conn.close()

    if expected is None:
        assert body["latest"] is None
    else:
        assert body["latest"] == main.jsonable(expected)
