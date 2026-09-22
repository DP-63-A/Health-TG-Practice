# BE1-03 core storage interface

This document describes the early BE1-03 storage slice used by BE2-02 and BE2-05.
It is not an HTTP API and Telegram handlers must not access Mongo repositories directly.

## Public Java interfaces

- `EntryCoreService.createCheckin(CreateCheckinCommand)` creates a confirmed quick check-in.
- `EntryCoreService.createDraft(CreateDraftCommand)` creates or returns the current draft.
- `EntryCoreService.findActiveDraft(OwnerContext)` restores the owner's current draft.
- `DialogStateService.save(SaveDialogStateCommand)` persists a conversation step and context.
- `DialogStateService.find(OwnerContext)` restores the conversation after process restart.

All calls require `OwnerContext`, produced from the authenticated user. A Telegram consumer supplies a
stable `TelegramUpdateKey(botKey, updateId)`. Re-delivery of that update returns the existing result.
Draft callers may additionally supply a stable `submissionId` for idempotency across transports.

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

Incomplete metric drafts are accepted: date and unit may be absent until confirmation. Numeric values
must be finite; steps and sleep values cannot be negative. Confirmation and transition validation belong
to the remaining BE1-03/BE1-04 work.

## Persistence

MongoDB collections:

- `entries` stores owner, type, status, source, occurrence time, payload, field origins and revision;
- `dialog_states` stores one current state per owner and uses optimistic locking.

Unique indexes protect Telegram update and submission idempotency. Owner-scoped reads never search by
entry id alone. Consumers receive domain records and service interfaces, not Spring Data repositories.

## Verification

`CoreStorageIntegrationTest` uses a real MongoDB Testcontainers instance and covers check-in boundaries,
concurrent duplicate delivery, submission idempotency, one active draft, incomplete metric drafts,
invalid numbers, owner isolation, and dialog-state restoration.
