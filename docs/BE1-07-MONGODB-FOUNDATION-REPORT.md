# BE1-07 environment implementation report

## Scope

This report started as the early MongoDB foundation and now tracks the complete
BE1-07 infrastructure implementation. Issue #5 remains open until the external
acceptance items listed below are completed.

Implemented:

- Compose services for MongoDB 8, API, Telegram bot and frontend;
- private shared network with only the frontend loopback port published;
- persistent `mongo-data` and private `file-data` named volumes;
- API and container healthchecks plus dependency ordering;
- safe `.env.example` and required-variable diagnostics;
- separate non-root API and bot runtime images;
- frontend live build served by Nginx with an API reverse proxy;
- GitHub Actions jobs for backend, frontend, contracts and Compose images;
- Java Checkstyle, frontend lint/typecheck/tests/build and contract validation;
- full build, run, restart, stop and diagnostic instructions.

## Automated verification

Historical command executed on 2026-09-23, before the API/bot module split:

```powershell
.\gradlew.bat clean :backend:test :backend:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL`. Backend tests, executable JAR creation, and BE1-01
contract validation passed.

The historical result above belongs to the original `:backend` module. With the
current split, the equivalent API checks use the following command (this is the
updated command, not a claim of another test run):

```powershell
.\gradlew.bat :backend:api:clean :backend:api:test :backend:api:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

The launcher now starts `:backend:api:bootRun`; the Telegram bot remains a separate
application. See [local environment](local-environment.md) and [API instructions](../backend/api/README.md).

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

## Current acceptance status

| BE1-07 criterion | Status | Evidence |
|---|---|---|
| AC1 Compose starts agreed components | Implemented, verification pending | Compose defines MongoDB, API, bot and frontend using one database |
| AC2 persistent private storage | Implemented, restart proof pending | Named MongoDB/file volumes; neither is published to the host |
| AC3 safe environment configuration | Implemented | Required substitutions, ignored `.env`, safe example and diagnostics |
| AC4 CI | Implemented | Backend style/tests/JARs, frontend checks, contracts and image builds |
| AC5 explicit modes and no paid calls | Implemented | CI uses fixtures/placeholders; Compose frontend is explicitly `live` |
| AC6 clean reproduction and seed/reset | Blocked externally | Requires BE3-05 commands, HTTPS stand and another participant |
| AC7 configuration/log audit | Partial | Secret-safe defaults are configured; human log audit still required |

## External acceptance work

- run the full Compose stack with authorized credentials and agreed HTTPS;
- verify ordinary restart after a database record and, after BE1-05, a file upload;
- connect and execute the protected BE3-05 seed/reset commands when delivered;
- reproduce the README from a clean environment by another participant;
- record green GitHub Actions evidence for the final reviewed commit;
- inspect runtime logs for absence of secrets and user health content.
