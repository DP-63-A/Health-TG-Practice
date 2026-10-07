# BE3-03 analytics endpoint

## Scope

`GET /api/v1/analytics` is implemented in the shared Spring Boot API. It uses
the BE1 bearer session, reads the current owner's confirmed entries through
`EntryCoreService`, delegates calculations to BE3-02 `AnalyticsFunctions`, and
returns the BE3-01 response shape. There is no second database, auth mechanism,
or analytics cache.

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

## Acceptance status

| Criterion | Status | Evidence |
|---|---|---|
| AC1 full authenticated endpoint over the shared read layer | Implemented, locally verified | `AnalyticsController`, `AnalyticsService`, `AnalyticsHttpIntegrationTest` |
| AC2 BE3-02 calculations over current confirmed entries | Implemented, locally verified | API depends on `:analytics`; storage query is `listConfirmedEntries` |
| AC3 periods, gaps, observations and sources | Implemented, locally verified | BE3-01 contract validator and HTTP integration assertions |
| AC4 PATCH/delete freshness and owner isolation | Implemented, locally verified | Testcontainers sequence and two authenticated owners |
| AC5 FE2 contract, tests, commit and review | Partial | BE3-01 response is implemented; commit, CI and reviewer evidence remain pending |

## Evidence record

- tested commit: pending;
- local integration run: passed on Windows, Java 21 and Docker Desktop, 2026-10-07;
- full backend, executable JAR and contract validation run: passed locally;
- GitHub Actions run: pending;
- reviewer and decision: pending.
