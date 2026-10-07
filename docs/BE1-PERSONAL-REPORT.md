# BE-1 personal report

## Contribution boundary

The BE-1 contribution covers API contracts, authentication/session ownership,
core persistence and lifecycle operations, protected file storage, Compose/CI,
backend acceptance and integration evidence. Recognition, domain analytics,
Telegram conversation UX and frontend presentation belong to their respective
owners; BE-1 changes there were limited to reviewed integration fixes.

## Delivered work

| Area | Evidence |
|---|---|
| BE1-01 contracts | PR #44 and contract validation |
| BE1-02 Telegram authentication and sessions | PR #50 |
| BE1-03 core storage and bot integration follow-up | PRs #52 and #76 |
| BE1-04 entry lifecycle API | PR #74 |
| BE1-05 protected image storage | PR #95 |
| BE1-06 backend acceptance and analytics-owner follow-up | PRs #121 and #125 |
| BE1-07 Compose, CI and environment | PR #116 |

The final tested commits and CI links belong in each task report and in the
BE1-08 acceptance record; merge commit presence alone is not acceptance proof.

## Defect, cause and regression

During bot/core integration, a transient MongoDB failure was initially handled
like an ordinary bad update. Advancing the Telegram offset could lose the
unpersisted operation, while retrying without durable idempotency could duplicate
data. The fix keeps the update unacknowledged for transient storage failures,
uses bounded backoff and relies on durable update/submission identifiers. Bot
runtime and storage regression tests cover retry, restart and duplicate delivery.

## Checks of AI-assisted solutions

1. **Partial clarification recovery.** A proposed implementation persisted a
   partial parser result but did not consume it on the next message. Review
   followed the state across restart, added continuation/merge behavior and
   required a restart test. Merely asserting that `text_clarification` was saved
   was rejected as insufficient.
2. **Analytics ownership acceptance.** The first generated test put both owners'
   step samples on the same day. Because analytics selects one latest daily
   sample, a random UUID tie could hide a leak. Peer review identified the weak
   oracle; the corrected test uses different days in `days_7`, so leaked data
   necessarily changes total, days, series and sources (`b160cd0`).

These examples verify persistence semantics and aggregation rules instead of
accepting green tests or generated code at face value.

## Peer review

Substantive reviews were performed for the Health Watch integration and the
date/time picker integration, including repeat/restart, ownership and storage
failure paths. Add the final PR review URLs here before acceptance:

- Health Watch review: Pending URL.
- Date/time picker review: Pending URL.
- BE1-08 reviewer: Pending assignment and URL.

## Defence notes

- **Authentication:** Telegram `initData` is signature- and age-validated; the
  server issues an opaque expiring session. Protected routes derive identity
  from that session, never from a client-supplied owner ID.
- **Owner isolation:** public services receive `OwnerContext`; repository reads
  and mutations are owner-scoped. Foreign resources are not disclosed.
- **Atomic revision:** a mutation includes `expected_revision`; MongoDB replaces
  only the matching current document. A competing stale request receives 409.
- **Idempotency:** Telegram update keys and confirmation submission IDs are
  durable unique operation identities, so replay returns the prior result rather
  than creating another logical record.
- **Small defence change:** a suitable exercise is to add one malformed or
  foreign-owner case to an existing HTTP acceptance test and explain why the
  assertion fails before the guard and passes afterward. Record the actual
  defence request and commit here; do not prepare an undisclosed change in
  advance.

## Personal acceptance

| BE-1 criterion | Status | Evidence |
|---|---|---|
| Contracts and error model | Implemented | Contract schemas, examples and validator |
| Closed access and owner guard | Implemented, live retest pending | Auth/file/entry/analytics integration tests; G-11 |
| Durable core and idempotency | Implemented, live retest pending | Core storage and bot/core tests; G-07 |
| Revision and lifecycle API | Implemented, live retest pending | Entry HTTP tests and 409 UI recovery; G-06/G-07 |
| Reproducible environment and CI | Implemented, external reproduction pending | Compose/CI and G-01 |
| Integration, review and explanation | Partial | Reviews performed; links, clean run and human decision pending |

The personal report does not replace the shared live scenarios or human
acceptance in `BE1-08-INTEGRATION-ACCEPTANCE.md`.
