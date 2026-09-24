package org.healthtg.core.entry;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CreateDraftCommand(
        OwnerContext owner,
        EntryType type,
        SourceKind sourceKind,
        Map<String, Object> sourceRef,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, String> fieldOrigins,
        String submissionId,
        TelegramUpdateKey updateKey
) {
    public CreateDraftCommand {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceKind, "sourceKind");
        sourceRef = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(sourceRef)));
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(payload)));
        fieldOrigins = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fieldOrigins)));
        if (submissionId != null && submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        Objects.requireNonNull(updateKey, "updateKey");
    }

    public CreateDraftCommand(OwnerContext owner, EntryType type, SourceKind sourceKind,
                              Map<String, Object> sourceRef, Instant occurredAt, Map<String, Object> payload,
                              Map<String, String> fieldOrigins, TelegramUpdateKey updateKey) {
        this(owner, type, sourceKind, sourceRef, occurredAt, payload, fieldOrigins, null, updateKey);
    }
}
