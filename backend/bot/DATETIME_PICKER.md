# Date/time picker in Telegram

The bot offers a WebApp **reply keyboard** button beside the message input. The existing inline actions and text input remain available. The ordinary check-in button remains on the keyboard. A successful selection continues clarification or edits a draft; it never confirms an Entry.

## Deployment

Build the frontend normally with `npm run build`. The output includes both `index.html` (authenticated diary) and `datetime-picker.html` (isolated input form). Serve both over HTTPS. `MINI_APP_URL` is the frontend deployment base, including an optional path prefix, not a diary route or an HTML filename. For example, base `https://example.test/app/` requires `/app/datetime-picker.html` alongside the main frontend. Preserve the existing frontend asset-prefix configuration when deploying under a prefix.

The picker does not fetch Entry data, use diary sessions, or call the HTTP API. It works only when opened through a Telegram reply WebApp button; inline WebApp buttons cannot use this return protocol. The bot and API remain separate programs.

## Protocol and state

The URL fragment carries UI hints, timezone, dialog revision and an opaque token. The page removes the fragment from its current history entry after reading it and does not persist it. Do not log URLs or raw WebApp data. Fragment values are not authentication.

Each persisted flow transition rotates a random `picker_nonce` in the existing DialogState context. The returned token binds that nonce to owner, dialog revision, step and timezone. The owner is derived from the allowed private Telegram sender. The bot validates the current form and server state rather than accepting URL-supplied field permissions.

`Telegram.WebApp.sendData` sends a compact JSON object:

```json
{"v":1,"token":"<opaque token>","revision":12,"date":"2026-10-07","time":"14:30"}
```

`time` is omitted for date-only forms. Duplicate fields, trailing JSON, excessive input and invalid field sets are rejected. The form closes when Telegram transmits the data; only the bot's reply confirms that storage succeeded.

## Date semantics

- Initial note/food clarification fills missing required date/time values together. Already known fields remain fixed.
- Draft note/meal correction can edit date and time; keeping the time unchanged preserves its value.
- Metrics edit `payload.local_date` only: steps total day, sleep wake date, or heart-rate measurement date. `local_time` and the original message `occurred_at` remain unchanged.
- Unknown values stay empty. There is no implicit current date/time. Java resolves event timestamps in the profile timezone; DST gaps and overlaps are rejected for an explicit new choice.
- Manual choices use `reported` origins; unrelated values retain their origins.

## Recovery and boundaries

Dialog actions run in the existing single synchronized polling worker. The dialog store does not provide a compare-and-swap API for an observed revision, so multiple concurrent bot writers are not supported by this implementation. Entry mutations still enforce owner and expected Entry revision against concurrent diary edits.

Existing durable `draft_pending` intents recover a PATCH after a lost response. Creation retains the existing Telegram update idempotency key; a created Entry is recovered after a failure saving the review state. Stale windows cannot mutate a subsequent step or a newer Entry revision. Cancel in the window closes it without changing the dialog.

Verify transport mapping, pre-entry text/food/metric flows, draft correction, old windows after manual input, repeats, foreign senders, DST, external edits, storage failures and restart. Run Java unit/Mongo integration checks and frontend typecheck/tests/build/lint. Live acceptance must exercise both mobile and desktop Telegram; an SDK build or browser-only test does not prove the live round trip.
