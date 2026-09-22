# Backend

Java 21 / Spring Boot backend. BE1-02 provides Telegram Mini App authentication,
opaque 60-minute sessions, `/api/v1/me`, and the shared current-user/owner guard.

## Environment

```text
TELEGRAM_BOT_TOKEN=<secret bot token>
TELEGRAM_ALLOWED_USER_IDS=<comma-separated numeric ids>
MONGODB_URI=mongodb://localhost:27017/health_tg
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Secrets and real Telegram IDs must not be committed or included in test reports.

## Verification

```powershell
.\gradlew.bat :backend:test :backend:bootJar validateContracts --console=plain
```

The owner guard is ready for BE1-04, BE1-05, and BE3-03. Their real route-level
negative tests remain an integration dependency of BE1-02.

## Core storage

BE2 consumers use `EntryCoreService` and `DialogStateService`; they must not use
Mongo repositories directly. The early BE1-03 interface and persistence rules are
documented in [`../docs/BE1-03-CORE-STORAGE.md`](../docs/BE1-03-CORE-STORAGE.md).
