# BE1-06 backend acceptance matrix

This matrix maps the mandatory BE1-02--05 server invariants to executable tests.
The dedicated command is:

```powershell
.\gradlew.bat be1Acceptance --no-daemon --console=plain
```

Docker is mandatory. `Be1AcceptanceIntegrationTest` uses `@Testcontainers`
without `disabledWithoutDocker`, so the dedicated task fails instead of silently
skipping the MongoDB proof. Every test uses a disposable container database and
clears only documents created inside that database; no configured application
or training database is addressed.

| Requirement | Test evidence | Storage / route |
|---|---|---|
| Valid Telegram login, invalid signature and expired initData | `AuthHttpIntegrationTest` | Real auth route; controlled clock and isolated stores |
| Missing, changed and expired session | `AuthHttpIntegrationTest` | Security filter and real protected route |
| Foreign entry is not disclosed or mutated | `Be1AcceptanceIntegrationTest.realRoutesDoNotRevealAnotherOwnersEntry` | MockMvc plus real core and MongoDB |
| Foreign file, missing session and expired session | `FilesHttpIntegrationTest.downloadRequiresValidSessionAndDoesNotRevealForeignFiles` | Real file route and MongoDB metadata |
| Concurrent PATCH of one revision | `Be1AcceptanceIntegrationTest.concurrentPatchOfOneRevisionHasOneSuccessOneConflictAndOneResult` | Two real HTTP requests; one 200, one 409, one MongoDB document |
| Repeated confirm | `Be1AcceptanceIntegrationTest.repeatedConfirmReturnsOneConfirmedDocument` and `CoreStorageIntegrationTest.concurrentConfirmIsIdempotentAndTransitionsRemainExcludedFromDiary` | HTTP replay and concurrent core transition |
| Repeated Telegram update and one active draft | `CoreStorageIntegrationTest.keepsOneActiveDraftAndDeduplicatesTelegramUpdate` | Unique MongoDB indexes |
| Concurrent quick check-in delivery | `CoreStorageIntegrationTest.createsConfirmedCheckinAndDeduplicatesConcurrentTelegramDelivery` | Real MongoDB uniqueness |
| Cancel, delete and stale revision | `CoreStorageIntegrationTest.concurrentConfirmIsIdempotentAndTransitionsRemainExcludedFromDiary`, `staleCancelCannotUndoNewerPatch`, `concurrentCancelWithSameRevisionIsIdempotent` | Atomic revision and status transitions |
| Draft/dialog restoration after restart | `CoreStorageIntegrationTest.restoresDraftAndDialogStateAfterApplicationContextRestart`, `BotCoreStorageIntegrationTest.botFlowsPersistThroughCoreAndRestoreAfterRestart` | Recreated Spring context over the same MongoDB database |
| Bot uses public core services | `BotCoreStorageIntegrationTest`, `QuickCheckinAcceptanceTest`, `TextDialogStorageTest` | Bot to core to MongoDB |
| Stored-file optimistic version and controlled missing bytes | `FilesHttpIntegrationTest` | MongoDB `@Version`, protected HTTP route and private filesystem |
| Foreign analytics | Pending BE3-03 merge | Must be added as a real authenticated route test; not claimed complete |

The ordinary `check` task retains the broad unit and regression suite. The
dedicated acceptance task is a named CI step so its execution is visible and
cannot be confused with fixture-only tests.
