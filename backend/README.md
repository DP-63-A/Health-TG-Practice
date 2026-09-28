# Backend

Backend consists of two independently launched applications and one shared Java library on Java 21:

| Module | Purpose | Documentation |
|---|---|---|
| `:backend:core` | Shared entry, draft, check-in, dialog-state and MongoDB persistence services | [BE1-03 storage](../docs/BE1-03-CORE-STORAGE.md) |
| `:backend:api` | HTTP API, Telegram authentication, sessions, ownership checks and readiness | [API](api/README.md) |
| `:backend:bot` | Telegram long polling, closed access, commands and keyboards | [Bot](bot/README.md) |

`api` depends on `core` and provides its Spring/MongoDB runtime. The bot remains isolated from MongoDB
until BE2-02/05 wires it to the public `EntryCoreService` and `DialogStateService` interfaces. Consumers
must not access Spring Data repositories directly. The `core` module is a library: it has no `main`, HTTP
port or independent process. API and bot remain separate applications and neither starts the other.

## Build and tests

From the repository root on Java 21:

```powershell
.\gradlew.bat :backend:core:test :backend:api:test :backend:bot:test :backend:api:bootJar :backend:bot:bootJar
```

On Linux/macOS use `sh ./gradlew` instead of `.\gradlew.bat`. MongoDB integration tests in the API
module require Docker; bot tests require neither a token nor network access. The aggregate
`:backend:build` task checks all three modules.

## Separate application startup

Start the API and bot in different terminals:

```powershell
.\gradlew.bat :backend:api:bootRun
```

```powershell
.\gradlew.bat :backend:bot:bootRun
```

API requires MongoDB. Bot requires its token and allowlist. Direct Gradle, JAR and IDEA launches do not
load `.env` automatically. `scripts/run-backend.ps1` loads the root `.env` and starts only the API.
See [local environment](../docs/local-environment.md).

After changing the structure, reload all Gradle projects in IDEA. Use classpath `backend.api.main` for
`HealthTgApplication` and `backend.bot.main` for `BotApplication`. Never commit local secrets or IDs.
