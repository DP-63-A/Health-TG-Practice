package org.healthtg.core.entry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PatchEntryCommand(OwnerContext owner, UUID entryId, long expectedRevision,
                                Instant occurredAt, Map<String, Object> payload,
                                Map<String, String> fieldOrigins) {
    public PatchEntryCommand {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(entryId);
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be positive");
        payload = payload == null ? null : Map.copyOf(payload);
        fieldOrigins = fieldOrigins == null ? null : Map.copyOf(fieldOrigins);
    }
}
