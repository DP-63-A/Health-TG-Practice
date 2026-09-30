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
- `DialogStateService.clearIfCurrent(owner, activeEntryId, expectedRevision, updateKey)` clears only
  the observed draft conversation. It returns `false` when the state no longer references that draft,
  its dialog revision changed, the update is stale, or the MongoDB version check loses a race. The
  operation never retries against a newer state; dialog and entry revisions are independent.

Callers use the state returned by `save()` as authoritative. A repeated or older update returns the
current persisted state without accepting the proposed context, so check-in keyboards must render its
selector ID. Draft cancellation observes the dialog before cancelling the entry and conditionally
clears only that same observed state afterwards.

All calls require `OwnerContext`, produced from the authenticated user. A Telegram consumer supplies a
stable `TelegramUpdateKey(botKey, updateId)`. Re-delivery of that update returns the existing result.
Draft creation uses the stable Telegram update key for delivery idempotency. `submissionId` remains
null until the first BE1-04 confirmation attempt.

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
steps, sleep, meal mass and nutrients cannot be negative. Confirmation and transitions are provided by
BE1-04.

## Telegram integration

`backend:bot` depends on `backend:core` and invokes only its public services. `CoreBotFlow` maps the
allowlisted Telegram identity through the shared `UserService`, so bot-created entries and API diary
reads use the same owner UUID. Parsed text is passed to `EntryCoreService.createDraft`; a completed
quick-checkin selection is passed to `EntryCoreService.createCheckin`. Active drafts are never
overwritten: the bot offers the common BE1-04 revision-protected cancel operation instead.

Check-in category/score steps and draft review state are persisted with `DialogStateService`. Selector
context is restored from MongoDB before processing a callback after application restart. Telegram
handlers do not import Spring Data repositories or `MongoTemplate`.

Useful partial parser results that require clarification are stored as versioned dialog context without
inventing missing values. The next answer is combined with the stored original input and passed through
the same parser again. Stored payload, field origins and temporal fields are then used as the merge base,
so a parser result containing only newly clarified fields cannot discard earlier data. This continuation
also works after application restart. Invalid or incompatible
persisted selector context is logged without personal data and reset to `idle`. Callback redelivery
restores the last accepted callback, so it returns the current keyboard without repeating a storage
operation.

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
| AC1 | `BotCoreStorageIntegrationTest` and core MongoDB tests | Bot text/check-in flows invoke public core services | Verified |
| AC2 | Active-draft and dialog-state persistence tests | Existing drafts are returned without overwrite; a separate-context restart test verifies MongoDB restoration | Verified |
| AC3 | Concurrent delivery, submission id and stale-update tests | One logical result; `201 -> 202 -> 201` does not roll state back | Verified in core |
| AC4 | Payload, score, ownership and confirmed-read tests | Contract validation and owner isolation are enforced without an LLM | Verified in core |
| AC5 | `BotCoreStorageIntegrationTest` plus full Gradle verification | Bot to core to MongoDB and restart restoration passed; human review remains | Verified automatically; review pending |

BE1-04 extends this storage interface with atomic patch, confirm, cancel and logical-delete operations;
its HTTP and concurrency evidence is documented in `docs/BE1-04-ENTRIES-API.md`.

The full Java 21 build passed on 2026-09-29 with Docker enabled. Test reports contained 53 API tests,
287 bot tests and 30 analytics tests, with zero failures and zero skipped tests. The verified commit must
be recorded in the PR after committing these changes. Commands used:

```powershell
.\gradlew.bat :backend:api:test --tests org.healthtg.persistence.CoreStorageIntegrationTest --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat :backend:bot:test --tests org.healthtg.bot.CoreBotFlowTest --tests org.healthtg.bot.BotCoreStorageIntegrationTest --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat clean :backend:core:test :backend:api:test :backend:bot:test :analytics:test :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Final acceptance still requires review by another participant.
