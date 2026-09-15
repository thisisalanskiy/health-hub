# Health Hub Backend (optional, self-hosted)

A small FastAPI + Postgres service that ingests Health Connect data and
serves it back out through a JSON API and a built-in browser dashboard.

**This is entirely optional.** The [Android app](../android-app) is
local-first: it reads Health Connect and computes everything it shows
(TDEE, trends, the Claude digest) on the device by default, with no
server involved. This backend exists only for people who want a
second, browser-accessible dashboard and/or a historical data store —
enable it from the app's Settings screen ("Sync to self-hosted
server", off by default) once it's running.

## What's here

- `main.py` — FastAPI app: `POST /ingest`, `GET /healthz`, a set of
  `GET /api/*` read endpoints, and a Chart.js dashboard served at `/`.
- `tdee.py` — the Python TDEE calculation the `/api/tdee` endpoint
  uses (the Android app has its own on-device Kotlin port of the same
  math for the no-backend path).
- `Dockerfile`, `requirements.txt` — container image for the API.
- `../docker-compose.yml` (repo root, not this folder) — runs the API
  container.
- `../db/init.sql` and `../db/migrations/*.sql` — schema, applied in
  order (`init.sql` first, then `001_...` through `004_...`).

## Requirements

- Docker Desktop (or another Docker Engine + Compose).
- A Postgres instance reachable from the API container. **The compose
  file does not run its own Postgres container** — `DATABASE_URL`
  defaults to `host.docker.internal:5432`, i.e. a Postgres the API
  reaches on your host machine (or wherever you point it). Run
  Postgres however you like — Homebrew, apt, a separate Docker
  container on the same network, a managed instance — then adjust
  `DATABASE_URL`/`POSTGRES_*` if it isn't on `localhost:5432` with the
  default credentials below.

## 1. Set up the database

Create a database and load the schema, in order:

```bash
createdb health_dashboard
psql health_dashboard -f db/init.sql
for f in db/migrations/*.sql; do psql health_dashboard -f "$f"; done
```

(Run these from the repo root so the relative paths resolve.)

## 2. Configure

Environment variables (all optional — shown with their defaults):

| Variable            | Default                | Purpose                                      |
|---------------------|-------------------------|-----------------------------------------------|
| `POSTGRES_USER`     | `hcuser`                | DB user                                       |
| `POSTGRES_PASSWORD` | `hcpassword`            | DB password                                   |
| `POSTGRES_DB`       | `health_dashboard`      | DB name                                       |
| `INGEST_API_KEY`    | *(unset — no auth)*     | If set, `POST /ingest` requires this value in the `X-API-Key` header, returning `401` otherwise |
| `LOCAL_TZ`          | `UTC`                   | Timezone used for day-bucketing in a few `/api/*` endpoints |

Put overrides in a `.env` file next to `docker-compose.yml` (already
git-ignored) rather than editing the compose file.

**Set `INGEST_API_KEY` before exposing this beyond localhost.** By
default `/ingest` has no authentication.

## 3. Run it

From the repo root:

```bash
docker compose up --build -d
```

This builds and starts the `api` container on port 8000.

Check it's alive:

```bash
curl http://localhost:8000/healthz
# {"status":"ok"}
```

Open `http://localhost:8000/` in a browser for the built-in dashboard
(weight, recovery, sleep, workouts, nutrition, heart rate, steps).

## 4. Point something at `/ingest`

`POST /ingest` accepts a JSON object with any of these top-level keys,
each holding a Health Connect-shaped `{"records": [...]}` payload:
`weight`, `nutrition`, `exercise`, `heartRate`, `restingHeartRate`,
`steps`, `sleep`. Only the keys present are processed; missing ones
are treated as empty.

- From the **Health Hub Android app**: Settings → enter this server's
  base URL (e.g. `http://<your-tailscale-hostname>:8000`) and the
  `INGEST_API_KEY` value, then turn on "Sync to self-hosted server".
- From anything else (Tasker, a cron job, etc.): POST the same JSON
  shape directly. Example minimal payload:

  ```json
  {
    "weight": {
      "records": [
        {
          "metadata": { "id": "b8b78fbd-61f6-393e-bc1e-6d0b101a168a",
                        "dataOrigin": { "packageName": "com.example.scale" } },
          "time": 1786148049000,
          "weight": { "kilograms": 78.6 }
        }
      ]
    }
  }
  ```

  ```bash
  curl -X POST http://localhost:8000/ingest \
    -H "Content-Type: application/json" \
    -H "X-API-Key: <your key, if set>" \
    -d @payload.json
  # {"status":"ok","ingested":{"weight":1,"nutrition":0,"exercise":0,"heartRate":0,"restingHeartRate":0,"steps":0,"sleep":0,"skipped":0}}
  ```

## 5. Notes / known gaps

- **Idempotent by design**: most tables key on `(source, timestamp)`
  or a stable UUID, so re-sending overlapping data updates in place
  rather than duplicating.
- **`exerciseType` is a numeric Health Connect code**; `main.py` maps
  the common ones to labels (`HC_EXERCISE_TYPES`) and falls back to
  `Type <code>` for anything unmapped.
- **Full raw record is preserved** in a `raw` JSONB column on most
  tables, so nothing is lost even though only a subset of fields is
  promoted to typed columns.
- **No authentication by default.** Set `INGEST_API_KEY` before
  exposing this past localhost/your own VPN. There's no per-user
  auth, accounts, or multi-tenancy — this is a single-user, self-hosted
  tool.
- `scripts/backup_db.sh` and `scripts/import_apple_health.py` /
  `scripts/import_macrofactor.py` in the repo root are one-off/cron
  helpers for this database, not part of the API service itself.
