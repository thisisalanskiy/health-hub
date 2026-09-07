import importlib
import uuid

import psycopg2
import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def reload_app(monkeypatch):
    """Reload main without INGEST_API_KEY to make /ingest open."""
    monkeypatch.delenv("INGEST_API_KEY", raising=False)
    import main
    importlib.reload(main)
    return main


@pytest.fixture
def cleanup_ids(reload_app):
    ids = []
    yield ids
    if ids:
        conn = psycopg2.connect(reload_app.DATABASE_URL)
        cur = conn.cursor()
        cur.execute("DELETE FROM biometric_timeseries WHERE id = ANY(%s::uuid[])", ([str(i) for i in ids],))
        conn.commit()
        cur.close()
        conn.close()


def _row(main, record_id):
    conn = psycopg2.connect(main.DATABASE_URL)
    cur = conn.cursor()
    cur.execute(
        "SELECT type, unit, value, source FROM biometric_timeseries WHERE id = %s",
        (record_id,),
    )
    row = cur.fetchone()
    cur.close()
    conn.close()
    if row is None:
        return None
    # Convert Decimal value to float
    return (row[0], row[1], float(row[2]), row[3])


def test_vo2_max_inserted(cleanup_ids, reload_app):
    client = TestClient(reload_app.app)
    record_id = str(uuid.uuid4())
    cleanup_ids.append(record_id)
    resp = client.post("/ingest", json={
        "vo2Max": {"records": [{
            "metadata": {"id": record_id, "dataOrigin": {"packageName": "com.garmin.android.apps.connectmobile"}},
            "time": 1700000000000,
            "vo2MillilitersPerMinuteKilogram": 42.5,
        }]},
    })
    assert resp.status_code == 200
    row = _row(reload_app, record_id)
    assert row == ("HKQuantityTypeIdentifierVO2Max", "mL/min·kg", 42.5, "Garmin")


def test_heart_rate_variability_inserted(cleanup_ids, reload_app):
    client = TestClient(reload_app.app)
    record_id = str(uuid.uuid4())
    cleanup_ids.append(record_id)
    resp = client.post("/ingest", json={
        "heartRateVariability": {"records": [{
            "metadata": {"id": record_id, "dataOrigin": {"packageName": "com.garmin.android.apps.connectmobile"}},
            "time": 1700000000000,
            "heartRateVariabilityMillis": 55.3,
        }]},
    })
    assert resp.status_code == 200
    row = _row(reload_app, record_id)
    assert row == ("HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "ms", 55.3, "Garmin")


def test_body_fat_inserted_as_fraction(cleanup_ids, reload_app):
    client = TestClient(reload_app.app)
    record_id = str(uuid.uuid4())
    cleanup_ids.append(record_id)
    # Android sends the already-converted 0-1 fraction (0.257 for 25.7%), matching
    # the existing historical rows' convention -- the backend does not re-scale it.
    resp = client.post("/ingest", json={
        "bodyFat": {"records": [{
            "metadata": {"id": record_id, "dataOrigin": {"packageName": "com.garmin.android.apps.connectmobile"}},
            "time": 1700000000000,
            "percentage": 0.257,
        }]},
    })
    assert resp.status_code == 200
    row = _row(reload_app, record_id)
    assert row == ("HKQuantityTypeIdentifierBodyFatPercentage", "%", 0.257, "Garmin")


def test_bmr_inserted(cleanup_ids, reload_app):
    client = TestClient(reload_app.app)
    record_id = str(uuid.uuid4())
    cleanup_ids.append(record_id)
    resp = client.post("/ingest", json={
        "bmr": {"records": [{
            "metadata": {"id": record_id, "dataOrigin": {"packageName": "com.garmin.android.apps.connectmobile"}},
            "time": 1700000000000,
            "kilocaloriesPerDay": 1680.0,
        }]},
    })
    assert resp.status_code == 200
    row = _row(reload_app, record_id)
    assert row == ("HKQuantityTypeIdentifierBasalMetabolicRate", "kcal/day", 1680.0, "Garmin")
