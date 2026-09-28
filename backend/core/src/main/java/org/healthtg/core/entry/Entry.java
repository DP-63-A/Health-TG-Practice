package org.healthtg.core.entry;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record Entry(
        UUID id,
        UUID ownerId,
        EntryType type,
        EntryStatus status,
        SourceKind sourceKind,
        Map<String, Object> sourceRef,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        long revision,
        Map<String, Object> payload,
        Map<String, String> fieldOrigins,
        String submissionId,
        String telegramUpdateKey
) {
    public Entry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceKind, "sourceKind");
        sourceRef = immutableCopy(sourceRef);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        payload = immutableCopy(payload);
        fieldOrigins = Collections.unmodifiableMap(new LinkedHashMap<>(fieldOrigins));
        Objects.requireNonNull(telegramUpdateKey, "telegramUpdateKey");
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(source)));
    }
}
