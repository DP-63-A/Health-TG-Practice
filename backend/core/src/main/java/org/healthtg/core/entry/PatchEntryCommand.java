package org.healthtg.core.entry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;

public record PatchEntryCommand(OwnerContext owner, UUID entryId, long expectedRevision,
                                Instant occurredAt, Map<String, Object> payload,
                                Map<String, String> fieldOrigins) {
    public PatchEntryCommand {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(entryId);
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be positive");
        payload = nullableCopy(payload);
        fieldOrigins = nullableCopy(fieldOrigins);
    }

    private static <K, V> Map<K, V> nullableCopy(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
