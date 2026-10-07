# BE1-06 backend acceptance report

## Scope

BE1-06 consolidates the BE1-02--05 authentication, ownership, idempotency,
revision, lifecycle, restart and protected-file invariants. Existing focused
tests remain in their owning modules; this change adds only missing cross-layer
HTTP/MongoDB assertions and a repeatable aggregate task.

## Commands

```powershell
docker info
.\gradlew.bat be1Acceptance --no-daemon --console=plain
.\gradlew.bat check :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --no-daemon --console=plain
```

The first command is an explicit prerequisite. CI executes the same Docker
check before `be1Acceptance`. The suite creates isolated Testcontainers MongoDB
instances and never runs destructive cleanup against a configured external URI.
Testcontainers removes its containers after the JVM exits, so the normal run
needs no cleanup command. If a run is interrupted, its temporary containers can
be identified by the `testcontainers` label before removing them manually.

## Acceptance status

| Criterion | Status | Evidence |
|---|---|---|
| AC1 auth, ownership, replay, revision and lifecycle automation | Implemented, locally verified | See `BE1-06-TEST-MATRIX.md` and the successful dedicated Gradle task |
| AC2 atomicity on isolated MongoDB | Implemented, locally verified | Concurrent HTTP PATCH plus confirm/cancel/deduplication tests passed against Testcontainers MongoDB |
| AC3 reproducible locally and in CI, protected from production data | Implemented, locally verified | Testcontainers-only configuration, successful local run and explicit CI step |
| AC4 defect regressions | Partial | The full backend/contract regression command passed locally; final reviewed commit and linked defect decisions remain to be recorded |
| AC5 matrix, commit report and independent review | Implemented, independently verified | Matrix and commit-bound report exist; CI passed and an independent reviewer reproduced all 85 acceptance tests with no failures or skips |

## Known dependency

The required foreign-analytics route check depends on BE3-03. No analytics
controller is present in the baseline used for this report, so that row remains
explicitly pending and BE1-06 must not be described as fully accepted until it
is added and executed.

## Verification record

- tested commit: [`365d5173527f1fb527877ac080817ab3db1efc43`](https://github.com/DP-63-A/Health-TG-Practice/commit/365d5173527f1fb527877ac080817ab3db1efc43);
- local environment: Windows, Java 21 and Docker Desktop, verified 2026-10-07;
- `be1Acceptance`: passed locally;
- full backend/contract command: passed locally;
- GitHub Actions: [successful checks for the tested commit](https://github.com/DP-63-A/Health-TG-Practice/commit/365d5173527f1fb527877ac080817ab3db1efc43/checks);
- independent review: accepted; `be1Acceptance` reproduced with 85 tests passed, 0 failed and 0 skipped.
