# BE1-07 early MongoDB foundation

## Scope

This is an early integration result of BE1-07 required by BE1-03, BE2-02, and
BE2-05. It does not complete Issue #5.

Implemented:

- MongoDB 8 Compose service bound only to `127.0.0.1`;
- persistent named volume `health-tg-mongo-data`;
- container healthcheck;
- safe `.env.example` without credentials;
- PowerShell launcher that loads root `.env` values into the backend process;
- local environment run/restart/stop documentation;
- real `/api/v1/healthz` MongoDB readiness check;
- OpenAPI-compatible HTTP 503 error when MongoDB is unavailable;
- automated HTTP ready/unavailable tests.

## Automated verification

Command executed on 2026-09-23:

```powershell
.\gradlew.bat clean :backend:test :backend:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL`. Backend tests, executable JAR creation, and BE1-01
contract validation passed.

Compose validation:

```powershell
docker-compose --env-file .env.example config
```

Result: configuration resolved successfully with the MongoDB service, loopback
port binding, healthcheck, and persistent volume.

## Manual verification

Image: `mongodb/mongodb-community-server:8.0-ubi9-slim`.

Verified on 2026-09-23:

1. MongoDB `ping` returned `{ ok: 1 }`.
2. Existing `users` and `sessions` collections remained after replacing the
   manually started container with the Compose-managed container.
3. `docker-compose ps` reported the MongoDB container as healthy.
4. `/api/v1/healthz` returned HTTP 200 with `mongo=ok`.
5. After `docker-compose stop mongo`, `/api/v1/healthz` returned HTTP 503 without
   connection details. After the review fix, its body follows `ServiceUnavailable`:
   `code`, `message`, and `request_id`.
6. After `docker-compose start mongo`, readiness returned HTTP 200 again.

## Acceptance status for the early slice

| BE1-07 criterion | Status | Evidence |
|---|---|---|
| AC1 Compose starts agreed components | Partial | MongoDB is implemented; API, bot, frontend, and file storage remain |
| AC2 persistent private storage | Partial | MongoDB persistence and loopback binding verified; file storage remains |
| AC3 safe environment configuration | Partial | MongoDB/backend variables documented; complete stack validation remains |
| AC4 CI | Not implemented | Full backend/frontend/contracts workflow remains |
| AC5 explicit modes and no paid calls | Partial | Local Mongo mode is explicit; full live/fixture stack remains |
| AC6 clean reproduction and seed/reset | Not implemented | Requires BE3-05 and another participant |
| AC7 configuration/log audit | Partial | This slice exposes no secrets; full-stack audit remains |

## Remaining BE1-07 work

- API and Telegram bot containers using the shared backend;
- private persistent file storage;
- frontend build/serving and agreed HTTPS configuration;
- `/healthz` checks for all required processes;
- GitHub Actions for backend, frontend, and contracts;
- BE3-05 seed/reset commands;
- full environment documentation and clean reproduction by another participant.
