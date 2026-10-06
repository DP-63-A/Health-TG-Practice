package org.healthtg.seed;

import org.healthtg.core.entry.EntryType;

import java.time.ZonedDateTime;
import java.util.Map;

public record SyntheticEntry(
        String logicalKey,
        EntryType type,
        ZonedDateTime occurredAt,
        Map<String, Object> payload,
        Map<String, String> fieldOrigins,
        Map<String, Object> initialPayload,
        int deliveries,
        boolean cancelled
) {
    public SyntheticEntry {
        payload = Map.copyOf(payload);
        fieldOrigins = Map.copyOf(fieldOrigins);
        initialPayload = initialPayload == null ? null : Map.copyOf(initialPayload);
        if (logicalKey == null || logicalKey.isBlank() || deliveries < 1) {
            throw new IllegalArgumentException("Invalid synthetic entry");
        }
    }

}
