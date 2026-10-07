# BE3-03 analytics endpoint

## Scope

`GET /api/v1/analytics` is provided by the shared Spring Boot API in the current
`develop` baseline. This change verifies that endpoint through HTTP and
MongoDB integration tests, extends common `422 VALIDATION_ERROR` handling and
documents the resulting BE3-01 contract. The endpoint uses the BE1 bearer
session, reads only the current owner's confirmed entries through
`EntryCoreService` and delegates calculations to BE3-02 `AnalyticsFunctions`.
There is no second database, auth mechanism or analytics cache.

Supported query parameters:

- `period=today|days_7|days_21` (required);
- `timezone=<IANA ZoneId>` (optional, default `Europe/Warsaw`);
- `checkin_category=sleep_quality|digestion_comfort|wellbeing|mood`
  (optional, default `mood`).

Invalid parameters produce the common `422 VALIDATION_ERROR`; an absent or
expired session produces the common `401 UNAUTHORIZED` response.

## Verification

```powershell
docker info
.\gradlew.bat :analytics:test :backend:api:test validateContracts :contract-validator:validate --no-daemon --console=plain
```

`AnalyticsHttpIntegrationTest` uses a disposable Testcontainers MongoDB and a
fixed clock. It covers owner isolation, null semantics, periods, timezone
boundaries, daily metric selection, all four check-in cards, invalid parameters,
and the real PATCH/delete sequence `930 -> 847.5 -> 600`.

The branch also contains the independent BE3-04 calculation tests from #34.
Their expected values are expressed directly in test code so there is one
executable source of truth rather than an unused duplicate fixture.

## Acceptance status

| Criterion | Status | Evidence |
|---|---|---|
| AC1 full authenticated endpoint over the shared read layer | Verified against `develop` | `AnalyticsHttpIntegrationTest` exercises the existing endpoint through BE1 authentication and shared storage |
| AC2 BE3-02 calculations over current confirmed entries | Verified locally | API integration and independent BE3-04 tests exercise `AnalyticsFunctions` using current confirmed entries |
| AC3 periods, gaps, observations and sources | Verified locally | BE3-01 contract validation and HTTP integration assertions |
| AC4 PATCH/delete freshness and owner isolation | Verified locally | Testcontainers sequence and two authenticated owners |
| AC5 FE2 contract, tests, commit and review | Partial | Tested commit and independent review are recorded; successful CI evidence remains to be linked |

## Evidence record

- tested commit: `7a0ba7c`;
- local integration run: passed on Windows, Java 21 and Docker Desktop;
- full backend, executable JAR and contract validation run: passed locally;
- GitHub Actions run: pending;
- independent review: 95 analytics and API authentication tests passed on
  `7a0ba7c`; no code blockers reported, 2026-10-08.
