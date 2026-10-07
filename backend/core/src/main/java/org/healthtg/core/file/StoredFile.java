package org.healthtg.core.file;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredFile(UUID id, UUID ownerId, UUID entryId, String mediaType, String extension,
                         long size, int width, int height, String sha256, Instant createdAt) {
    public StoredFile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(extension);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(createdAt);
        if (size < 1 || width < 1 || height < 1) throw new IllegalArgumentException("Invalid file metadata");
    }
}
