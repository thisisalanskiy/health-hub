# Health Hub

A local-first Android health dashboard. It reads whatever's already in
[Health Connect](https://health.google.com/connect) — weight, sleep,
steps, heart rate, workouts, nutrition — and turns it into charts,
trends, and a locally-computed TDEE (total daily energy expenditure)
estimate, entirely on your device.

An optional self-hosted backend (FastAPI + Postgres) exists for people
who want a second, browser-accessible dashboard or a longer-term data
store. It is off by default and never required.

## Privacy

- **On-device by default.** The dashboard, its charts, and its TDEE
  calculation are computed from Health Connect data on the phone. A
  fresh install never talks to a network.
- **Backend sync is opt-in and self-hosted.** A single toggle in
  Settings ("Sync to self-hosted server") controls whether anything
  ever leaves the device — and even then, it POSTs to a server *you*
  run, not a third party.
- **No telemetry.** Nothing is collected, logged remotely, or sent to
  the app's developers. There's no analytics SDK in the app.
- **No accounts.** There's no sign-in, no cloud identity, nothing tied
  to an email address or user ID. All app state lives in the app's own
  local storage and Health Connect.
- **The "Claude digest" feature** assembles a text summary of your
  recent health data entirely on-device and copies it to your
  clipboard, for you to paste into a Claude conversation (or anywhere
  else) yourself. It does not call any AI API on its own.

## What it does

- Reads Health Connect: weight, sleep, steps, heart rate, resting
  heart rate, exercise/workouts, and nutrition (including
  background sync, so charts stay current without opening the app).
- Renders dashboard charts (weight trend, sleep stages, workouts,
  heart rate, steps, macros, TDEE) as hand-rolled Compose `Canvas`
  views — no third-party charting library.
- Computes TDEE on-device from your weight and calorie-intake history
  (a Kotlin port of the same regression the optional backend can also
  run in Python).
- Imports a [MacroFactor](https://macrofactorapp.com/) `.xlsx` export
  from Settings, so its own expenditure/trend-weight numbers can sit
  alongside the app's locally-calculated ones.
- Optionally POSTs synced data to a self-hosted backend (see below) if
  you turn that on, purely as a secondary store/dashboard — nothing
  the app itself shows depends on it.

## Build / setup

1. Open the `android-app/` folder in Android Studio (or build from the
   command line with `./gradlew :app:assembleDebug` from inside
   `android-app/`). Requires JDK 17.
2. Install [Health Connect](https://health.google.com/connect) on the
   test device/emulator if it isn't already present (built in on
   recent Android versions; installable from Play Store on older
   ones).
3. Install and launch the app. On first use of the dashboard, Android
   will prompt you to grant Health Connect permissions — the app asks
   for read access to weight, nutrition, exercise, heart rate,
   resting heart rate, steps, and sleep, plus background read access
   so it can sync periodically without being in the foreground. All
   of this is declared in `AndroidManifest.xml` and only ever *reads*
   Health Connect; the app writes nothing back to it.
4. If a permission is denied, the corresponding chart/section degrades
   quietly (shows a placeholder) instead of crashing — you can grant
   the rest later from Android's Health Connect settings.
5. Everything above works fully offline. No backend, account, or
   network access is required to use the dashboard.

## Optional: self-hosted backend

If you want a second dashboard reachable from a browser, or a
longer-lived data store outside the phone, you can run the small
FastAPI + Postgres stack in this repo:

- `docker-compose.yml` (repo root) — builds and runs the API
  container.
- [`api/README.md`](api/README.md) — full setup: database schema,
  environment variables, authentication, and how to point the Android
  app (or anything else) at it.

Once it's running, turn on "Sync to self-hosted server" in the app's
Settings and enter the server's base URL and API key. Leave it off if
you have no interest in running a server — the app is fully
functional without it.

## Repo layout

```
android-app/   the Android app (Kotlin, Jetpack Compose)
api/           optional FastAPI backend (see api/README.md)
db/            Postgres schema + migrations for the optional backend
docker-compose.yml   runs the optional backend's API container
scripts/       one-off import/backup helpers for the optional backend
docs/          design specs and implementation plans from past work
```

## License

TBD — license not yet chosen.
