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
operation. Do not use it on the training stand. BE3-05 has not yet supplied its
protected seed/reset commands; once available they must be wired here without
bypassing their demo-environment guard.

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
- connect the protected BE3-05 seed/reset commands when delivered;
- verify BE1-05 upload/download persistence when that API is available;
- have another participant reproduce these instructions in a clean environment.
