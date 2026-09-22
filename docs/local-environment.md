# Local environment

This is the early BE1-07 MongoDB foundation used by BE1-02 and BE1-03. The final
Compose stack will also include the API, bot, frontend, and private file storage.

## Prerequisites

- Docker Desktop with the engine running.
- Java 21 for running the backend from Gradle.

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

## Run the backend

```powershell
$env:MONGODB_URI="mongodb://localhost:27017/health_tg"
.\gradlew.bat :backend:bootRun --console=plain
```

Readiness check:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/healthz
```

Expected ready response:

```json
{"status":"ok","checks":{"mongo":"ok"}}
```

MongoDB failure returns HTTP 503 with `status=degraded` and no connection details.

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
