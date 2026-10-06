# Local environment

This is the early BE1-07 MongoDB foundation used by BE1-02 and BE1-03. The final
Compose stack will also include the API, bot, frontend, and private file storage.

## Prerequisites

- Docker Desktop with the engine running.
- Java 21 for running the API from Gradle.

## Configuration

Copy `.env.example` to `.env` and keep real secrets only in `.env`. Git ignores
`.env` and local variants. MongoDB requires no secret for this loopback-only local
development setup.

## Start MongoDB

```powershell
docker-compose up -d mongo
docker-compose ps
```

The database is bound to `127.0.0.1` and is not exposed on other host interfaces.

## Run the API

The API does not read `.env` by itself. Use the repository script to load
the file into the API process without printing its values:

```powershell
# Create once; keep an existing local .env.
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
.\scripts\run-backend.ps1
```

Edit `.env` before starting live Telegram authentication. The script fails with
a clear error when the file is missing or contains a malformed `NAME=VALUE` line.
Run it from the repository root; it starts Gradle from that root regardless of
the caller's current directory.

The script runs only `:backend:api:bootRun`. It loads the root `.env` by default;
use `-EnvFile <path>` to select another file. These values become environment
variables in the PowerShell process and are inherited by the API. Plain Gradle,
`java -jar`, and IDEA launches do not load `.env` automatically.

The Telegram bot runs separately with `:backend:bot:bootRun` or its IDEA
configuration. This API launcher does not start bot polling. See the
[bot instructions](../backend/bot/README.md) for its settings and manual checks.
Compose currently starts only MongoDB; it does not start either Java application.

Readiness check (API and MongoDB only):

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/healthz
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
docker-compose restart mongo
```

The named `health-tg-mongo-data` volume survives ordinary stop, start, restart,
and container recreation. Do not remove the volume during a normal restart.

## Stop

```powershell
docker-compose stop mongo
```

Removing containers without removing persistent data:

```powershell
docker-compose down
```

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

Use a dedicated local database. In `.env`, set `MONGODB_URI` to
`mongodb://localhost:27017/health_tg_demo`, set `HEALTH_TG_DEMO=true`, and provide
the existing internal user UUIDs in `BE3_05_REGULAR_USER_ID`,
`BE3_05_IRREGULAR_USER_ID`, and `BE3_05_INCOMPLETE_USER_ID`. These values are
user document IDs, not Telegram IDs. Keep actual Telegram authentication and
allow-list configuration unchanged; the seed tool does not create accounts or
replace live Telegram verification. Use three test accounts with access to the
demo database.

From PowerShell at the repository root:

```powershell
.\scripts\demo-data.ps1 -Command seed
.\scripts\demo-data.ps1 -Command seed -StartDate 2026-09-01 -Seed 20260505
```

The default seed is `20260505` and the default start date is `2026-09-01`.
The seed and start date are recorded in stable internal update keys: rerunning
with the same parameters verifies the same logical records without duplicates.
Different parameters intentionally identify a different dataset and can add
records; reset first if replacing a dataset. Run the API against the same
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
.\scripts\demo-data.ps1 -Command reset
```

Both commands refuse to run unless `HEALTH_TG_DEMO=true` and `MONGODB_URI`
targets the fixed `health_tg_demo` database on a loopback host. Reset never drops
a database or collection; it removes only `seed` entries bearing the BE3-05
dataset marker and one of the three fixed profile names. It does not remove
users, sessions, other seed data, or files. No educational file-storage
implementation or BE1-05 cleanup rule is present in this checkout, so file
cleanup is deliberately not attempted. Never point this command at the ordinary
`health_tg` database.
