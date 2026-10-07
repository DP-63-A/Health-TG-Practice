# BE1-05 private image storage

## Contract

The backend stores the original decoded JPEG or PNG in a private directory and
keeps owner/file/Entry metadata in MongoDB. Physical paths are never accepted from
the user and are never returned by an API.

BE2 uses the internal `FileStorageService` in two phases:

1. `store(owner, content, contentLength)` validates and stores the original and
   returns a server-generated UUID.
2. BE2 creates an Entry whose `source_ref.file_id` is that UUID.
3. `bindToEntry(owner, fileId, entryId)` verifies the owner and source reference,
   then atomically binds the file metadata to the Entry.

An unbound file is not downloadable. Rebinding to another Entry is rejected and
MongoDB optimistic locking prevents concurrent binds from silently overwriting one
another. Failed Entry creation can leave an inaccessible unbound file; it is kept
for a future protected training reset rather than deleted without an agreed policy.

## Validation

`store` reads at most 5 MiB plus one boundary byte. The server determines the type
from decoded content, not a filename, extension or caller-provided media type.
Only JPEG and PNG are accepted. Image dimensions are checked before allocation and
again after decoding; width times height must not exceed 12,000,000 pixels.

Stored metadata includes byte size, dimensions, media type and SHA-256. Reads
verify the current bytes against size and SHA-256 so a missing or corrupted
physical file returns a controlled `503 FILE_UNAVAILABLE` response.

## Authorized download

`GET /api/v1/files/{id}` requires the normal bearer session. Lookup always includes
the current owner ID. Unknown IDs, foreign IDs and unbound files all return the
same `404 RESOURCE_NOT_FOUND`; no response exposes whether another user owns the
UUID. Successful responses use the detected `image/jpeg` or `image/png`, a
server-derived inline filename and `Cache-Control: no-store`.

The frontend already downloads through `fetch` with `Authorization: Bearer ...`
and creates an in-memory blob URL. The bearer token is never placed in the URL.

## Storage and lifecycle

`FILE_STORAGE_ROOT` selects the private root (`./data/files` by default). Files are
placed below UUID-derived shard directories and use a `.bin` physical suffix.
The BE1-07 Compose stack mounts `/var/lib/health-tg/files` as the persistent
`file-data` volume for both API and bot and does not publish the directory.

Logical cancellation/deletion of an Entry does not remove its original. No expiry
or background cleanup is implemented because the retention period is an open
BE1/BE2/BE3 decision. The current BE3-05 reset removes only tagged synthetic
entries and therefore must not delete the shared file catalogue or `file-data`
volume: real bot entries would remain while their originals disappeared. A future
protected reset of user-owned training data must remove matching Entry records,
file metadata and physical files as one coordinated operation. `docker compose
down --volumes` is not the product reset operation.

## Verification

```powershell
.\gradlew.bat :backend:core:test :backend:api:test :backend:bot:test \
  :backend:api:bootJar :backend:bot:bootJar validateContracts \
  :contract-validator:validate --no-daemon --console=plain
```

Automated tests cover JPEG/PNG detection, corrupt and unsupported content, 5 MiB
and 12 MP limits, owner isolation, source-reference binding, unchanged original
bytes, corruption detection and safe HTTP headers.

The current dependent implementations are:

- `feature/be2-03-food-integration-9` for Telegram download, retry-safe storage,
  Entry creation and binding;
- `develop` for authorized Mini App retrieval through an in-memory blob URL;
- `issue-5-BE1-07-compose-ci` for the shared private `file-data` volume.

The following acceptance evidence still requires those branches to be merged and
a real training environment:

- Telegram download through BE2 -> store -> Entry -> bind;
- Mini App retrieval through the real session;
- ordinary BE1-07 Compose restart with the same image;
- a separately agreed protected reset for user-owned training data and review by
  another participant.
