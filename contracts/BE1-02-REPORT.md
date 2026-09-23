# BE1-02: Telegram authorization, sessions, and ownership

## Scope implemented

- Server-side validation of raw Telegram Mini App `initData` using HMAC-SHA256.
- Strict 15-minute `auth_date` limit and rejection of future timestamps.
- Telegram ID allowlist from environment configuration.
- Minimal internal user record with explicit `Europe/Warsaw` timezone.
- Opaque 256-bit Bearer tokens; only SHA-256 token hashes are stored.
- Server-side 60-minute sessions with an expiry check on every protected request.
- `POST /api/v1/auth/telegram` and `GET /api/v1/me` matching BE1-01.
- Shared `CurrentUser` and uniform-404 `OwnershipGuard` for downstream routes.
- Error bodies with `code`, `message`, and generated `request_id`.

No real Telegram IDs, bot tokens, initData strings, or session tokens belong in this report.

## Configuration

| Variable | Purpose |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Telegram signature verification secret |
| `TELEGRAM_ALLOWED_USER_IDS` | Comma-separated numeric allowlist |
| `MONGODB_URI` | MongoDB connection |
| `CORS_ALLOWED_ORIGINS` | Allowed Mini App frontend origins |

## Verification command

```powershell
.\gradlew.bat clean :backend:test :backend:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Final pre-commit run on 2026-09-23: `BUILD SUCCESSFUL`; 17 backend tests passed,
including MongoDB Testcontainers integration tests, the executable JAR was built, and BE1-01
contract validation passed. Record the verified commit SHA in the PR after committing.

Manual MongoDB verification on 2026-09-22 used the official
`mongodb/mongodb-community-server:8.0-ubi9-slim` image. The database responded to `ping`,
created `users` and `sessions`, created a unique `users.telegramId` index, and created a
TTL `sessions.expiresAt` index with `expireAfterSeconds: 0`. An unauthenticated request to
`GET /api/v1/me` returned the contract-compatible `401 UNAUTHORIZED` response.

## Acceptance status

| Criterion | Verification | Expected | Actual | Status | Evidence |
|---|---|---|---|---|---|
| AC1 | Unit, MockMvc, and MongoDB checks | Auth and `/me` match the contract; 15/60-minute boundaries are enforced | All 17 backend tests passed; real Mongo collections, persistence, and indexes verified | Passed | `TelegramInitDataVerifierTest`, `SessionServiceTest`, `AuthHttpIntegrationTest`, `MongoPersistenceIntegrationTest`; successful Gradle run on 2026-09-23 |
| AC2 | Signed user and allowlist tests | Client `user_id` is rejected; identity comes only from signed initData | Signature, tampering, unknown-field, and allowlist scenarios passed | Passed | `TelegramInitDataVerifierTest`, `AuthServiceTest`, `AuthHttpIntegrationTest` |
| AC3 | Owner guard unit test | Foreign ownership produces uniform not-found behavior | Guard implemented; real routes do not exist yet | Partially complete | `OwnershipGuardTest`; BE1-04/05 and BE3-03 remain integration dependencies |
| AC4 | Telegram -> FE1 -> `/me` | Real Mini App completes login and handles expiry | Backend CORS and API are prepared; live flow not run | Not verified | Requires FE1-01, HTTPS Mini App URL, bot access, and MongoDB |
| AC5 | Final build and human review | Commands, results, verified SHA, no secrets | Full build passed; commit SHA and human review remain | Partially complete | Successful Gradle run on 2026-09-23; complete in PR after commit and review |

## Remaining integration dependencies

- Run a real Telegram Mini App login against the deployed backend and FE1-01.
- Apply `CurrentUser`/`OwnershipGuard` to real entry, file, and analytics routes from BE1-04, BE1-05, and BE3-03.
- Verify two-user negative access scenarios on those routes.
