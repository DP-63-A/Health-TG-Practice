# Local environment

BE1-07 provides one Compose project for MongoDB, API, Telegram bot, frontend and
private persistent file storage. API and bot share the same MongoDB database.

## Prerequisites

- Docker Desktop with the engine running.
- Docker Compose v2.
- A dedicated training Telegram bot token and an allowed Telegram user ID.
- Java 21 and Node.js 22 only for running checks outside containers.

## Configuration

Copy `.env.example` to `.env` and replace every placeholder. Keep real secrets only
in `.env`; Git ignores it. `MINI_APP_URL` must be the agreed public HTTPS URL for a
real Telegram launch. Compose fails during configuration when required values are
missing and does not print their contents.

## Build and start

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

Only frontend is published, on loopback port `FRONTEND_PORT` (8088 by default).
MongoDB, API and the file volume have no host port or bind mount. Nginx forwards
`/api/` to the private API service. The file volume is reserved for BE1-05 and is
not a public file server.

Readiness check (API and MongoDB only):

```powershell
Invoke-RestMethod http://localhost:8088/api/v1/healthz
```

Expected ready response:

```json
{"status":"ok","checks":{"mongo":"ok"}}
```

MongoDB failure returns the OpenAPI `ServiceUnavailable` response without
connection details:

```json
{"code":"SERVICE_UNAVAILABLE","message":"Required service is unavailable","request_id":"req_..."}
```

## Restart without losing data

```powershell
docker compose restart
```

The `mongo-data` and `file-data` named volumes survive stop, start, restart and
container recreation. Do not pass `--volumes` during an ordinary restart.

## Stop

```powershell
docker compose stop
```

Removing containers without removing persistent data:

```powershell
docker compose down
```

`docker compose down --volumes` is destructive and is not the product seed/reset
operation. Do not use it on the training stand. Use the protected BE3-05 reset
command below for demo entries.

## BE3-05 synthetic demo dataset

The command-line tool creates three synthetic 21-day profiles in MongoDB:
`regular`, `irregular`, and `incomplete`. Each has meals, device metrics, and
quick notes; the incomplete profile has an intentionally empty day. The data is
fictional and is not a model of real health. It includes local morning/evening
times, gaps, varied values, a repeated delivery, a corrected draft, and a
cancelled draft. Metrics retain the configured account's IANA timezone, units,
status, `seed` source, and field origins. Existing entry APIs expose confirmed
records to the authenticated owner; drafts and cancelled entries remain subject
to the normal API status rules.

Use a dedicated local database. For the private Compose workflow, set
`MONGODB_DATABASE=health_tg_demo` and provide
the existing internal user UUIDs in `BE3_05_REGULAR_USER_ID`,
`BE3_05_IRREGULAR_USER_ID`, and `BE3_05_INCOMPLETE_USER_ID`. These values are
user document IDs, not Telegram IDs. Keep actual Telegram authentication and
allow-list configuration unchanged; the seed tool does not create accounts or
replace live Telegram verification. Use three test accounts with access to the
demo database. The Compose tool sets its protected demo flags internally; do not
add them to the long-running API or bot services.

From PowerShell at the repository root:

```powershell
.\scripts\demo-data.ps1 -Command seed -Compose
.\scripts\demo-data.ps1 -Command seed -StartDate 2026-09-01 -Seed 20260505 -Compose
```

The default seed is `20260505` and the default start date is `2026-09-01`.
The seed and start date are recorded in stable internal update keys: rerunning
with the same parameters verifies the existing record payload, type, owner, time,
field origins, source metadata, and final status without duplicates. A mismatch
stops the command and requires reset before reseeding. All three configured
accounts must exist and resolve to distinct user UUIDs before any records are
generated or written. Different parameters intentionally identify a different
dataset and can add records; reset first if replacing a dataset. Run the API against the same
`health_tg_demo` URI and authenticate normally to read entries through
`GET /api/v1/entries` (`from`, `to`, `limit=100`, and normal cursor pagination).
There is no HTTP seed route or profile-switching option.

For the default seed, the independent BE3-04/07 entry-count controls are:

| Profile | Meals | Metrics | Notes | Check-ins | Confirmed API entries | Other |
|---|---:|---:|---:|---:|---:|---|
| regular | 42 | 63 | 21 | 21 | 147 | one meal delivery is repeated |
| irregular | 33 | 34 | 7 | 6 | 80 | intentional meal/metric/note gaps |
| incomplete | 19 | 26 | 4 | 4 | 52 | one additional cancelled check-in; day 11 is empty |

These values count logical records, not delivery attempts. Incomplete's
cancelled check-in is excluded from the confirmed API count. The empty day is
the eleventh local date of the selected start date (2026-09-11 by default).

Reset only the BE3-05-tagged entries in the fixed local demo database:

```powershell
.\scripts\demo-data.ps1 -Command reset -Compose
```

Both commands run as an isolated one-shot container on the private Compose
network and refuse any database except `health_tg_demo` at the exact `mongo`
service host. MongoDB remains unpublished. The original host mode remains
available for a dedicated loopback MongoDB and still requires
`HEALTH_TG_DEMO=true` with a matching `MONGODB_URI`. Reset never drops
a database or collection; it removes only `seed` entries bearing the BE3-05
dataset marker and one of the three fixed profile names. It does not remove
users, sessions, other seed data, or files. No educational file-storage
implementation or BE1-05 cleanup rule is present in this checkout, so file
cleanup is deliberately not attempted. Never point this command at the ordinary
`health_tg` database.

The seed service is covered by a Testcontainers integration scenario against
MongoDB. It verifies first-seed count and content, repeat-seed idempotency,
reset behavior, and preservation of unrelated records. Run it with
`sh ./gradlew :backend:api:test --tests org.healthtg.seed.DemoDatasetMongoIntegrationTest`;
Docker must be available.

## Checks

```powershell
.\gradlew.bat check :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --no-daemon --console=plain
Set-Location frontend
npm ci
npm run lint
npm run typecheck
npm test -- --run
npm run build
Set-Location ..
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
```

CI uses synthetic fixtures, Testcontainers and placeholder configuration. It does
not use the training database, real Telegram credentials or paid model calls.

## Diagnostics

Use `docker compose ps` and `docker compose logs <service>`. Do not enable debug
logging for Telegram or HTTP clients: request URLs may contain the bot token.
Application logs must not include message text, images or health values. The API
returns `X-Request-ID`; readiness reports MongoDB failure as HTTP 503.

## External acceptance still required

- publish frontend through the agreed HTTPS host and set `MINI_APP_URL`;
- verify BE1-05 upload/download persistence through the Compose file volume;
- have another participant reproduce these instructions in a clean environment.
