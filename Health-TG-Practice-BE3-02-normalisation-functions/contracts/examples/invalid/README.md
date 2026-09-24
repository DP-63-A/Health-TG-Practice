# Invalid examples — expected rejection reasons

Schema validation strips `_rejection` before checking. Its `field` and `keyword` are checked against the validator's error location and type; `reason` explains the expected rejection to readers.

| File | Expected rejection |
|---|---|
| `entry-unknown-status.json` | `status` not in `draft\|confirmed\|cancelled\|deleted` |
| `entry-unknown-type.json` | `type` not in `meal\|metrics\|checkin\|note` |
| `entry-score-out-of-range.json` | `payload.score` outside 1–5 |
| `entry-negative-steps.json` | `payload.value` < 0 for `steps` |
| `entry-negative-sleep.json` | `payload.value` < 0 for `sleep_duration_min` |

Rules **not** covered by these schema checks (owner, confirm idempotency, status transitions, 409): see `../rules/runtime-invariants.md`.
