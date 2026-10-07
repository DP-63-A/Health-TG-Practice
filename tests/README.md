# End-to-end acceptance

This directory documents cross-module checks that cannot be represented by one
module's unit tests. Run the protocol from a clean clone at the commit named in
`docs/BE1-08-INTEGRATION-ACCEPTANCE.md`. Never put tokens, Telegram IDs, session
values, images or health data in the report.

## Prerequisites

- Docker Desktop with Compose v2;
- Git, Java 21 and Node.js 22;
- a dedicated training Telegram bot and three allow-listed test accounts for
  the demo profiles (two are sufficient for the owner-isolation checks);
- a public HTTPS URL for the Mini App (localhost is sufficient only for Compose
  readiness checks).

Create the ignored environment file and replace its placeholders locally:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env config --quiet
```

Keep MongoDB, API and file storage private. Only the frontend reverse proxy is
published on `127.0.0.1`; Telegram opens the same frontend through the agreed
HTTPS host. Do not enable live photo recognition unless that paid external call
is explicitly part of the test.

### Temporary HTTPS for Windows acceptance

For a non-production manual run, download `cloudflared-windows-amd64.exe` from
the [official Cloudflare GitHub releases](https://github.com/cloudflare/cloudflared/releases/latest)
and keep this command running in a separate PowerShell window:

```powershell
& "C:\path\to\cloudflared-windows-amd64.exe" tunnel --url http://localhost:8088
```

Copy the generated `https://...trycloudflare.com` origin without a trailing
slash into the private `.env`:

```dotenv
MINI_APP_URL=https://generated-host.trycloudflare.com
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:5173,https://generated-host.trycloudflare.com
```

After either value changes, apply the new environment by recreating the affected
containers (plain `docker compose restart` does not reread `.env`):

```powershell
docker compose --env-file .env up -d
```

Compose preserves the named data volumes during this recreation. A quick tunnel
has no uptime guarantee and its address changes after restart; it is suitable
only for manual acceptance. Stop it with `Ctrl+C`. A permanent stand must use
the team's managed HTTPS host instead.

## Automated baseline

```powershell
docker info
.\gradlew.bat be1Acceptance --no-daemon --console=plain
.\gradlew.bat check :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --no-daemon --console=plain
Set-Location frontend
npm ci
npm audit --omit=dev
npm run lint
npm run typecheck
npm test -- --run
npm run build
Set-Location ..
docker compose --env-file .env config --quiet
docker compose --env-file .env build
```

Record the commit, OS, Docker, Java and Node versions and exact pass/fail result.
A green baseline does not replace the following live scenarios.

## G-01: clean start

The verifier must be someone other than the author and must not edit code or the
database manually.

1. Clone the repository into a new directory and check out the tested commit.
2. Create `.env` from `.env.example`; enter only private training credentials.
3. Set `MONGODB_DATABASE=health_tg_demo` for this disposable acceptance stand,
   then run `docker compose --env-file .env up --build -d`.
4. Run `docker compose ps`; `mongo`, `api` and `frontend` must be healthy and
   `bot` must remain running.
5. Request `http://localhost:8088/api/v1/healthz`; expect MongoDB status `ok`.
6. Authenticate the three test accounts normally. Read their internal UUIDs
   without changing MongoDB:

   ```powershell
   docker compose --env-file .env exec -T mongo mongosh --quiet health_tg_demo --eval 'db.users.find({}, {_id:1, telegramId:1}).sort({telegramId:1}).forEach(printjson)'
   ```

   Match the local Telegram IDs to `_id`, put only the UUID values in the
   private `BE3_05_*_USER_ID` variables, and do not copy this output to reports.
7. Run `.\scripts\demo-data.ps1 -Command seed -Compose` twice. Both runs must
   succeed and confirmed entry counts must remain 147, 80 and 52.
8. Run `.\scripts\demo-data.ps1 -Command reset -Compose` twice, then seed once
   more. Reset must preserve accounts and unrelated records.
9. In the bot run `/state` (or press `Отметить состояние`), select a category
   and score, then open the HTTPS Mini App and confirm that the check-in appears
   after reopening the application.
10. Restart the stack with `docker compose restart`; the entry must remain.
11. Stop without deleting volumes: `docker compose down`.

Record every deviation and the corrective commit. Do not use `down --volumes`
for the persistence check.

## G-06: recalculation 930 -> 847.5 -> 600

Use one test account and one local date inside the selected analytics period.

1. Send two meal descriptions to the bot. For each resulting draft, open it in
   the Mini App, set energy to 600 and 330 kcal respectively, then confirm it.
2. Open live Overview and verify 930 kcal in both the card and daily series.
3. Edit the second entry to 247.5 kcal using its current revision.
4. Refresh Overview and verify 847.5 kcal.
5. Delete the second entry using its current revision.
6. Refresh Overview and verify 600 kcal; the deleted entry must not appear in
   Diary or analytics sources.

Capture entry IDs, revisions and request IDs only. The automated regression is
`AnalyticsHttpIntegrationTest.returnsOwnedAnalyticsAndReflectsPatchAndDelete`.

## G-07: replay and version conflict

1. Automated prerequisite: run
   `Be1AcceptanceIntegrationTest.repeatedConfirmReturnsOneConfirmedDocument`;
   it repeats the same `submission_id` and proves that only one record exists.
   The product UI intentionally does not expose raw sessions or submission IDs.
2. For the live part, open one draft in two browser sessions. Save a change in the first session,
   then submit the stale revision from the second; expect HTTP 409.
3. Verify that the stale UI keeps the user's input, shows the conflict and lets
   the user reload the server version before making a conscious retry.
4. Redeliver a recorded Telegram update in the automated acceptance suite;
   verify that no second record is created.

Evidence: `Be1AcceptanceIntegrationTest`, `CoreStorageIntegrationTest` and the
409 recovery scenarios in `EntryPage.test.tsx`.

## G-11: closed access

1. Call a protected route without a session, with an invalid session and with
   an expired session; each request must return 401 without secret details.
2. Authenticate as account A and request entry, file and analytics data owned by
   account B. Foreign entries/files must not be disclosed; account A analytics
   must contain only account A source IDs.
3. Send a bot command from a non-allow-listed account and from a group chat;
   protected functions must remain unavailable.
4. Inspect the report, logs and tracked files for tokens, real Telegram IDs,
   session values and user images. None may be present.

Evidence: `AuthHttpIntegrationTest`, `FilesHttpIntegrationTest`,
`Be1AcceptanceIntegrationTest` and bot access tests.

## Recording the result

For every scenario enter `PASS`, `FAIL` or `BLOCKED`, the tested commit, commands,
environment, verifier and evidence in
`docs/BE1-08-INTEGRATION-ACCEPTANCE.md`. A blocked mandatory step keeps BE1-08
open. Fixture-only runs must be labelled as fixtures.
