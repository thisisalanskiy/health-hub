# Health Connect Ingestion Backend

Minimal Docker Compose stack: Postgres + a FastAPI endpoint that accepts
the combined Weight / Nutrition / Exercise JSON pulled by Tasker and
writes it into three tables.

## Instructions for Claude Code

If you're an agent setting this up, do the following in order and
report back after each numbered step, stopping if anything fails:

1. Confirm Docker Desktop is installed and running (`docker --version`,
   `docker compose version`). If not installed, stop and tell the user
   to install Docker Desktop for Mac first.
2. From the root of this unzipped folder, run:
   ```bash
   docker compose up --build -d
   ```
3. Wait a few seconds for Postgres to initialize, then verify the API
   is reachable:
   ```bash
   curl http://localhost:8000/health
   ```
   Expected: `{"status":"ok"}`. If this fails, run
   `docker compose logs api` and `docker compose logs db` and report
   the output.
4. Create `test_payload.json` in this folder using the exact JSON shown
   under "2. Test the ingest endpoint manually" below, then run the
   `curl -X POST .../ingest` command shown there.
5. Confirm the response matches
   `{"status":"ok","ingested":{"weight":1,"nutrition":1,"exercise":1,"skipped":0}}`.
   If `skipped` is nonzero or the counts don't match, report the exact
   response and do not proceed.
6. Verify rows actually landed in Postgres using the `psql` command
   under step 2 below, for all three tables (`weight_records`,
   `nutrition_records`, `exercise_records`).
7. Once all three tables show the test row, report success and the
   current `docker compose ps` output back to the user. Do not modify
   `main.py`, `init.sql`, or `docker-compose.yml` unless the user
   explicitly asks for a schema or logic change.
8. Note for the user, don't silently fix: this API currently has no
   authentication on `/ingest`. If the user asks to expose this beyond
   localhost/VPN, flag that first rather than just doing it.

## 1. Run it

From this folder:

```bash
docker compose up --build -d
```

This starts:
- `db`: Postgres 16, port 5432, schema auto-created from `db/init.sql`
- `api`: FastAPI app, port 8000

Check it's alive:

```bash
curl http://localhost:8000/health
# {"status":"ok"}
```

## 2. Test the ingest endpoint manually

Before pointing Tasker at this, confirm it accepts data with a manual
POST. Save this as `test_payload.json` (trimmed example, one record
per type):

```json
{
  "weight": {
    "records": [
      {
        "metadata": {
          "id": "b8b78fbd-61f6-393e-bc1e-6d0b101a168a",
          "dataOrigin": { "packageName": "com.oceanwing.smarthome" }
        },
        "time": 1786148049000,
        "weight": { "kilograms": 78.6 }
      }
    ]
  },
  "nutrition": {
    "records": [
      {
        "metadata": {
          "id": "72c20175-8c11-4069-add6-786855f09245",
          "dataOrigin": { "packageName": "com.sbs.diet" }
        },
        "startTime": 1786153978853,
        "endTime": 1786154038853,
        "name": "Test Meal",
        "energy": { "kilocalories": 191.0 },
        "protein": { "grams": 30.0 },
        "totalCarbohydrate": { "grams": 18.1 },
        "totalFat": { "grams": 0.0 }
      }
    ]
  },
  "exercise": {
    "records": [
      {
        "metadata": {
          "id": "1f514ba6-74b0-35b7-8d01-fa88f8fe70bf",
          "dataOrigin": { "packageName": "com.whoop.android" }
        },
        "startTime": 1785907080790,
        "endTime": 1785908421056,
        "exerciseType": 79
      }
    ]
  }
}
```

Then:

```bash
curl -X POST http://localhost:8000/ingest \
  -H "Content-Type: application/json" \
  -d @test_payload.json
```

Expected response:

```json
{"status":"ok","ingested":{"weight":1,"nutrition":1,"exercise":1,"skipped":0}}
```

Verify it landed in Postgres:

```bash
docker compose exec db psql -U hcuser -d health_dashboard -c "SELECT * FROM weight_records;"
```

## 3. Point Tasker at it

Update the HTTP Request action's URL to:

```
http://<your-server-ip>:8000/ingest
```

Body: the combined JSON object with `weight`, `nutrition`, and
`exercise` keys, each set to the corresponding Read Data action's
output variable, matching the shape above.

If your server isn't on the same network as your phone, you'll need
either a VPN back to it (e.g. Tailscale) or a reverse proxy exposing
it externally with authentication in front. Don't expose port 8000
directly to the public internet without at least an API key check;
this initial version has no auth on the endpoint.

## 4. Notes / known gaps

- **Idempotent by design**: each record's Health Connect `id` is used
  as the primary key, so re-sending the same record (e.g. Tasker
  re-fetching an overlapping time window) updates in place rather
  than duplicating.
- **`exerciseType` is a numeric code**, not a readable label (e.g. 79,
  81). A lookup table mapping these to names hasn't been added yet.
- **Optional shared-key authentication** on `/ingest`: by default (local dev),
  the endpoint is open and requires no authentication. To enable authentication,
  set the `INGEST_API_KEY` environment variable (e.g. in a `.env` file next to
  `docker-compose.yml`) to a shared secret; when set, `POST /ingest` requires
  that value in the `X-API-Key` request header and returns `401` otherwise.
- **Full raw record is preserved** in the `raw` JSONB column on every
  table, so nothing is lost even though only a few fields are
  extracted into typed columns. Query `raw` directly for anything not
  yet promoted to its own column (e.g. micronutrients).
