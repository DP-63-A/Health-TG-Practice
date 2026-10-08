# BE1-08 integration acceptance

## Scope

This report joins BE1-01 through BE1-07 with the implemented BE2, BE3 and
frontend flows. It follows the BE1-08 card and BE-1 appendix of
`student_project_complete_7673.pdf` v1.0 dated 2026-09-16. Commands and expected
results are defined in `tests/README.md`.

## Tested revision and environment

| Field | Result |
|---|---|
| Tested application commit | [`287958a`](https://github.com/DP-63-A/Health-TG-Practice/commit/287958ae44529123692efa04a9b54b66089dfd60); the BE1-08 PR changes documentation only |
| Tested documentation commit | Pending review-fix commit and CI link |
| Verification date | 2026-10-08 |
| Verifier (not the author) | Pending team assignment; automated baseline below was run by the author |
| OS / architecture | Windows, x86-64 |
| Docker / Compose | Docker Desktop; client 28.1.1; exact Compose version to record during clean run |
| Java / Node | Temurin 21.0.12.1; Node 24.16.0; npm 11.13.0 |
| HTTPS host | Pending; do not record credentials or private URL parameters |
| Training accounts | Two private accounts required; identifiers must not be recorded |

## Automated baseline

| Check | Status | Evidence |
|---|---|---|
| BE1 acceptance | PASS locally | `gradlew be1Acceptance`; completed successfully on 2026-10-08 |
| Backend, contracts and executable jars | PASS locally | Root Gradle verification command completed successfully on 2026-10-08 |
| Frontend lint, types, tests and build | PASS locally | audit: 0 production vulnerabilities; lint/typecheck/build passed; 31 files and 356 tests passed |
| Compose config, images and readiness | Pending final commit | CI will verify config/build/readiness; independent reproduction remains in G-01 |

## Cross-module protocol

| Scenario | Status | Actual result / evidence | Defect or corrective commit |
|---|---|---|---|
| G-01 clean clone, start, quick check-in, restart | BLOCKED: external verifier and HTTPS run required | Pending | Pending |
| G-06 930 -> 847.5 -> 600 in live history and analytics | BLOCKED: coordinated live run required | Automated API regression exists; live result pending | Pending |
| G-07 replay, one record, stale revision 409 and UI recovery | BLOCKED: coordinated live run required | Automated backend and frontend regressions exist; live result pending | Pending |
| G-11 invalid/expired login and foreign objects unavailable | BLOCKED: two-account live run required | Automated auth, owner, file and analytics isolation exists; live result pending | Pending |

## Findings and retest log

| Date | Scenario | Finding | Owner / linked task | Retest |
|---|---|---|---|---|
| 2026-10-08 | Automated baseline | Initial frontend typecheck/test start failed with `ENOSPC`; no application assertion failed | Local disk capacity, not an application defect | Generated artifacts/cache were cleared; lint, typecheck, 31/356 tests and build then passed |

## Acceptance criteria

| Criterion | Status | Evidence still required |
|---|---|---|
| AC1 clean start by another participant | Pending | Named verifier, environment, commands and result |
| AC2 mandatory BE1 scenarios with real modules | Partial | Automated coverage exists; complete the four live rows above |
| AC3 README matches verified project state | Partial | Another participant must reproduce it without code/database edits |
| AC4 personal report, defect, two AI checks and peer review | Partial | See `BE1-PERSONAL-REPORT.md`; add final review links and acceptance |
| AC5 BE-1 criteria and human acceptance | Pending | Human decision tied to the final commit |

## Human decision

- Decision: Pending (`accepted`, `accepted with limitations`, or `rejected`).
- Reviewer: Pending.
- Date: Pending.
- Reviewed commit: Pending.
- Unfinished work explicitly accepted: Pending.

BE1-08 is not complete while any mandatory row above remains pending or blocked.
