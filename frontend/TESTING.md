# Frontend diary checks (FE1-06)

The FE1-02--05 UI suite uses Vitest, jsdom, and Testing Library. It runs with
controlled API responses or fixture data; it does not require MongoDB, Telegram,
an LLM, or a backend process.

## Local commands

From `frontend/`:

```sh
npm ci
npm run typecheck
npm test
npm run lint
npm run build
```

`npm test` already runs `vitest run` (non-watch). CI should run the same command.

## Requirement matrix

| Area | Behavioral/API evidence | Remaining boundary |
| --- | --- | --- |
| FE1-02 loading, empty, error, retry | `src/components/ui/StateView.test.tsx`; `src/pages/DiaryPage.test.tsx` holds a list request pending, then returns empty, and retries a failed GET to visible data | No browser/device visual acceptance |
| FE1-02 session expired | `src/auth/AuthProvider.test.tsx`; `src/pages/EntryPage.test.tsx` verifies DELETE 401 displays the shared session screen without delete success | Real token expiry is FE1-07 |
| FE1-03 filters and pagination | `src/pages/DiaryPage.test.tsx` checks visible results and exact `status/from/to/type/limit/cursor` arguments, reset to page 1, and old-response rejection; `src/api/entries.test.ts` checks facade query forwarding | Real backend cursor semantics are FE1-07 |
| FE1-03 source and ownership-safe file URL | `src/pages/DiaryPage.test.tsx` checks source/revision and token-free protected file URL; `src/pages/EntryPage.test.tsx` checks source/origins; both cover object URL cleanup races | Real protected file authorization is FE1-07 |
| FE1-04 forms | `src/pages/EntryPage.test.tsx` loads draft/confirmed, checks meal/metrics/note and all four checkin categories, units, origins, unknown/null, dates and DST | Real server validation is FE1-07 |
| FE1-04 PATCH/confirm/cancel | `src/pages/EntryPage.test.tsx` checks ID, expected revision, submission ID, PATCH-then-confirm revision, no early success, double clicks, and shared refresh | Bot/shared-draft protocol is FE1-07 |
| FE1-04 errors | `src/pages/EntryPage.test.tsx` checks local validation without PATCH, 422 nested field errors, input preservation, network error, 409 without automatic overwrite/resubmit | Real concurrent BE1 mutation is FE1-07 |
| FE1-05 DELETE/conflict | `src/pages/EntryPage.test.tsx`, `src/api/entries.test.ts`, and `src/api/fixtureClient.test.ts` check confirmed-only DELETE, quoted If-Match, pending/error/401/409 states, safe recovery, revision and deleted status | Real logical delete is FE1-07 |
| FE1-05 refresh/races | `src/refresh/RefreshProvider.test.tsx`, `src/pages/DiaryPage.test.tsx`, and `src/pages/EntryPage.test.tsx` check mutation/manual/lifecycle rereads, multiple subscribers, lifecycle coalescing, unsaved input, and stale Diary/Entry/Overview responses | Actual analytics recalculation and five-second target are integration checks |

## Regression checks

- `DiaryPage.test.tsx`: an old list response cannot replace a newer filter or
  refresh result; an opaque server cursor is used and cleared on filter change.
- `EntryPage.test.tsx`: a late PATCH/DELETE response cannot change another
  entry; a 409 keeps local input; accepting a stale N+1 snapshot cannot replace
  N+2; lifecycle rereads preserve unsaved edits and unknown dates stay unknown.
- `RefreshProvider.test.tsx`: visibility/focus/pageshow for one return produce
  one reread; pending timers and listeners are cleaned up; old Overview data
  cannot replace newer analytics.
- `fixtureClient.test.ts`: stale revision, quoted If-Match, logical deletion,
  and confirm idempotency follow the fixture contract.

Negative paths are not inferred from snapshot or render-only tests: API arguments
and user-visible results are asserted together in the page tests. The shared
fixture API is reloaded per fixture transition test, so mutations in one test
cannot become another test's starting state. Lifecycle timer tests use a fixed
clock and restore real timers. Deferred responses control pending/race outcomes.

## BE1-07 CI handoff

No `.github/workflows` directory exists at this commit. The BE1-07 owner can add
these commands to the project workflow when it is created, using its chosen
Node setup and runner, without a parallel frontend-only workflow or backend
secrets:

```yaml
- run: npm ci
  working-directory: frontend
- run: npm run typecheck
  working-directory: frontend
- run: npm test
  working-directory: frontend
- run: npm run build
  working-directory: frontend
```

This block is a handoff, not evidence that CI has run. FE1-07 must separately
verify the real API, Telegram/bot concurrency, analytics, permissions, and file
access. Human review and mobile acceptance also remain separate.
