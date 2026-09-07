import psycopg2
from fastapi.testclient import TestClient

import main


def test_body_trends_matches_independently_computed_values():
    client = TestClient(main.app)

    resp = client.get("/api/body-trends?days=30")
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"dates", "vo2_max", "body_fat_percent", "bmr_kcal"}
    assert len(body["dates"]) == len(body["vo2_max"]) == len(body["body_fat_percent"]) == len(body["bmr_kcal"])

    conn = psycopg2.connect(main.DATABASE_URL)
    cur = conn.cursor()
    cur.execute("""
        SELECT date_trunc('day', start_time)::date AS day, type, AVG(value)::float
        FROM biometric_timeseries
        WHERE type IN (
            'HKQuantityTypeIdentifierVO2Max',
            'HKQuantityTypeIdentifierBodyFatPercentage',
            'HKQuantityTypeIdentifierBasalMetabolicRate'
        )
        AND start_time >= NOW() - INTERVAL '1 day' * 30
        GROUP BY 1, 2
    """)
    expected = {}
    for day, typ, val in cur.fetchall():
        expected.setdefault(main.jsonable(day), {})[typ] = val
    cur.close()
    conn.close()

    type_to_key = {
        "HKQuantityTypeIdentifierVO2Max": "vo2_max",
        "HKQuantityTypeIdentifierBodyFatPercentage": "body_fat_percent",
        "HKQuantityTypeIdentifierBasalMetabolicRate": "bmr_kcal",
    }
    for i, d in enumerate(body["dates"]):
        for typ, key in type_to_key.items():
            exp = expected.get(d, {}).get(typ)
            got = body[key][i]
            if exp is None:
                assert got is None
            else:
                assert got == exp
