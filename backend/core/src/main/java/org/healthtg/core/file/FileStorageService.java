package org.healthtg.core.file;

import org.healthtg.core.entry.OwnerContext;

import java.io.InputStream;
import java.util.UUID;

public interface FileStorageService {
    StoredFile store(OwnerContext owner, InputStream content, long contentLength);
    StoredFile bindToEntry(OwnerContext owner, UUID fileId, UUID entryId);
    StoredFileContent open(OwnerContext owner, UUID fileId);
}
