# BE1-04 entries API

The diary API is exposed under `/api/v1/entries`. Every route requires a bearer session and derives
the owner from that session. Missing and foreign identifiers both return `404 RESOURCE_NOT_FOUND`.

## Routes

- `GET /entries` lists confirmed entries by default. `status=draft` lists active drafts. Optional
  filters are `from`, `to`, and `type`; `limit` is 1 through 100 and defaults to 20. `next_cursor`
  is opaque and must be returned unchanged by clients.
- `GET /entries/{id}` returns the current entry and an `ETag` containing its quoted revision.
- `PATCH /entries/{id}` accepts `expected_revision` and partial `occurred_at`, `payload`, and
  `field_origins` fields. Payload objects are merged at their top level and then validated.
- `POST /entries/{id}/confirm` accepts `submission_id` and `expected_revision`. Repeating the same
  submission for the same entry returns the already confirmed result.
  Draft creation always leaves `submission_id` null; the key is assigned by the first confirm attempt.
- `POST /entries/{id}/cancel` cancels a draft and requires `If-Match: "<revision>"`. Repeating the
  same completed cancellation is idempotent; a stale action after a newer patch returns 409.
- `DELETE /entries/{id}` logically deletes a confirmed entry and requires `If-Match: "<revision>"`.

Every successful single-entry response includes the new revision as a strong ETag. A stale mutation
returns `409 VERSION_CONFLICT`; `field_errors` contains the current revision so the client can reload.
Invalid transitions return `409 INVALID_STATUS_TRANSITION`, and malformed input returns
`422 VALIDATION_ERROR`.

## Atomicity and history

Mutations use one MongoDB `findAndModify` operation filtered by entry id, owner id, status, and
revision. A concurrent request cannot pass by doing a separate unprotected read and save. The previous
value is pushed into the same document during that operation; only the latest ten snapshots are kept.

Only `confirmed` entries are returned by the default diary query and by the BE3 confirmed-entry core
query. Cancelled and deleted entries remain stored for audit and do not become analytics facts.

## Payload and nutrition behavior

Drafts may remain incomplete where the payload contract permits that. Confirmation performs stricter
validation: confirmed metrics require non-null `unit` and `local_date`.

For a meal with `nutrients_basis=per_100g`, changing `mass_g` does not rewrite the nutrient reference
values. BE3-02 calculates the portion total from those values and the current mass. With
`per_serving`, the stored nutrient values also remain unchanged. The end-to-end G-06 assertion through
`/api/v1/analytics` is implemented in the API module; its calculation/read-path integration tests are
in `AnalyticsServiceTest`, `AnalyticsHttpIntegrationTest`, and `DemoDatasetMongoIntegrationTest`.

## Examples

Patch revision 2:

```http
PATCH /api/v1/entries/11111111-1111-4111-8111-111111111111
Authorization: Bearer <session>
Content-Type: application/json

{
  "expected_revision": 2,
  "payload": { "mass_g": 150 },
  "field_origins": { "mass_g": "reported" }
}
```

Confirm a draft:

```http
POST /api/v1/entries/11111111-1111-4111-8111-111111111111/confirm
Authorization: Bearer <session>
Content-Type: application/json

{
  "submission_id": "sub_01JABC",
  "expected_revision": 3
}
```

Delete revision 4:

```http
DELETE /api/v1/entries/11111111-1111-4111-8111-111111111111
Authorization: Bearer <session>
If-Match: "4"
```

## Acceptance status

| Criterion | Current verification | Status |
|---|---|---|
| AC1 | Controller implements all OpenAPI entry routes | Implemented; full build pending |
| AC2 | Concurrent patch test, CAS query, bounded MongoDB history | Verified |
| AC3 | Concurrent confirm, cancel, delete and transition tests | Verified in core |
| AC4 | Owner-isolation and payload-validation tests | Verified in core |
| AC5 | Mass mutation storage test; FE/BE2 and `/analytics` acceptance | Partially verified |

The proposed pagination, date-boundary, uniform-404, and If-Match decisions follow the current
OpenAPI and `contracts/OPEN-DECISIONS.md`. Human consumer acceptance is still required before those
proposals are treated as final project decisions.
