package org.healthtg.core.file;

import org.healthtg.core.entry.OwnerContext;

import java.io.InputStream;
import java.util.UUID;

public interface FileStorageService {
    enum CleanupResult { DELETED, PROTECTED }
    /** Retry-safe cancellation cleanup. A protected or uncertain Entry association is never deleted. */
    CleanupResult discardUnreferenced(OwnerContext owner, UUID fileId);
    StoredFile store(OwnerContext owner, InputStream content, long contentLength);
    /** Retry with the same ID requires the same owner and image contents. */
    StoredFile store(OwnerContext owner, UUID fileId, InputStream content, long contentLength);
    StoredFile bindToEntry(OwnerContext owner, UUID fileId, UUID entryId);
    StoredFileContent open(OwnerContext owner, UUID fileId);
}
