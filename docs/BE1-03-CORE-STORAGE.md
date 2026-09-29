# BE1-03 core storage interface

This document describes the BE1-03 core storage implementation used by BE2-02 and BE2-05.
It is not an HTTP API and Telegram handlers must not access Mongo repositories directly.

## Public Java interfaces

- `EntryCoreService.createCheckin(CreateCheckinCommand)` creates a confirmed quick check-in.
- `EntryCoreService.createDraft(CreateDraftCommand)` creates or returns the current draft.
- `EntryCoreService.findActiveDraft(OwnerContext)` restores the owner's current draft.
- `EntryCoreService.listConfirmedEntries(ListConfirmedEntriesQuery)` returns the owner's current
  confirmed entries for an inclusive local-date period and optional entry-type filter.
- `DialogStateService.save(SaveDialogStateCommand)` persists a conversation step and context.
- `DialogStateService.find(OwnerContext)` restores the conversation after process restart.

All calls require `OwnerContext`, produced from the authenticated user. A Telegram consumer supplies a
stable `TelegramUpdateKey(botKey, updateId)`. Re-delivery of that update returns the existing result.
Draft callers may additionally supply a stable `submissionId` for idempotency across transports.

## Confirmed entry reads

BE3 consumers pass trusted owner context, inclusive `from`/`to` dates, an explicit IANA timezone and
an optional set of entry types. The service returns only that owner's `confirmed` entries. Draft,
cancelled and deleted entries are excluded at the storage boundary. Period membership is calculated
from `occurredAt` in the requested timezone; payload `local_date` does not override that rule. Results
are ordered by occurrence time and entry id.

## Errors

- `EntryValidationException` reports a payload or active-entry reference that violates the contract;
- `EntryOwnershipException` reports an update-key or active-entry ownership violation;
- `DialogStateConflictException` reports an exhausted retry after concurrent MongoDB conflicts;
- command records may throw `IllegalArgumentException` for malformed constructor arguments.

## Check-ins

Supported categories are:

- `sleep_quality`;
- `digestion_comfort`;
- `wellbeing`;
- `mood`.

The score is an integer from 1 through 5. A check-in is stored immediately with `confirmed` status,
revision 1, `quick_checkin` source, reported field origins, and the supplied occurrence time. The core
does not invoke an LLM.

## Drafts

Only one entry with `draft` status may exist for an owner. A second creation request returns that draft
without overwriting it. The database enforces this rule with a partial unique index, so concurrent calls
cannot create two active drafts.

Draft payloads are validated by entry type against the agreed contract fields. Unknown fields and
invalid enum values are rejected. Incomplete metric drafts are accepted: `unit` and `local_date` may be
absent until confirmation, while `code` and `value` remain required. Numeric values must be finite;
steps, sleep, meal mass and nutrients cannot be negative. Confirmation and transition validation belong
to the remaining BE1-03/BE1-04 work.

## Persistence

MongoDB collections:

- `entries` stores owner, type, status, source, occurrence time, payload, field origins and revision;
- `dialog_states` stores one current state per owner, the highest processed Telegram update per bot,
  and uses optimistic locking.

Unique indexes protect Telegram update and submission idempotency. Owner-scoped reads never search by
entry id alone. A non-null active dialog entry must exist and belong to the dialog owner. Re-delivered
or older Telegram updates cannot overwrite a newer state; concurrent creates and transitions are retried
after duplicate-key or optimistic-lock conflicts. Consumers receive domain records and service
interfaces, not Spring Data repositories.

Telegram update ids are monotonic within a bot key. The dialog document stores the highest processed id
for each bot key instead of an unbounded event history. A repeated or lower id returns the current saved
state without applying another transition.

## Verification

`CoreStorageIntegrationTest` uses a real MongoDB Testcontainers instance and covers check-in boundaries,
concurrent duplicate delivery, submission idempotency, one active draft, contract-shaped incomplete
metric drafts, invalid payloads, owner isolation, active-entry ownership, stale update rejection,
concurrent first state creation, and dialog-state restoration. The restart scenario closes one Spring
application context, creates a new context against the same MongoDB database, and verifies that both the
active draft and dialog state survive the restart unchanged.

## Acceptance status

| Criterion | Verification | Current result | Status |
|---|---|---|---|
| AC1 | MongoDB tests for `createDraft` and `createCheckin` | Core methods are covered; invocation from the Telegram bot belongs to BE2-02/05 | Partially verified |
| AC2 | Active-draft and dialog-state persistence tests | Existing drafts are returned without overwrite; a separate-context restart test verifies MongoDB restoration | Implemented; final rerun required |
| AC3 | Concurrent delivery, submission id and stale-update tests | One logical result; `201 -> 202 -> 201` does not roll state back | Verified in core |
| AC4 | Payload, score, ownership and confirmed-read tests | Contract validation and owner isolation are enforced without an LLM | Verified in core |
| AC5 | Java interface documentation and full Gradle verification | Core interface is documented; bot/API/FE end-to-end checks and human acceptance remain external | Partially verified |

Full bot usage and diary UI verification remain assigned to BE2-02/05 and FE1-03. BE1-04 now extends
this storage interface with atomic patch, confirm, cancel and logical-delete operations; its HTTP and
concurrency evidence is documented in `docs/BE1-04-ENTRIES-API.md`.

The full Java 21 build passed after merging `develop` at commit
`8e547000d329b6515cc0f0afe2873dfa151906e3`. A separate-context restart regression test was then added;
the following checks must be rerun and recorded for the final commit before merge:

```powershell
.\gradlew.bat :backend:api:test --tests org.healthtg.persistence.CoreStorageIntegrationTest --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat clean :backend:core:test :backend:api:test :backend:bot:test :analytics:test :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

The verified commit and final results must be recorded in the PR description after these changes are
committed. AC1 and AC5 remain partial until BE2 invokes the core services and the available end-to-end
flow is accepted by another participant.
