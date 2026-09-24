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

Destructive reset is intentionally not part of the ordinary workflow. The final
reset command belongs to the BE3-05 integration and must be documented separately.
