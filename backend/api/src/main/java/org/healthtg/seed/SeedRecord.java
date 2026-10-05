package org.healthtg.seed;

import org.healthtg.core.entry.EntryType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One logical synthetic record. {@code index} is stable for a given profile, random seed and start date and is
 * used as the Telegram-style update id that makes writes idempotent.
 */
public record SeedRecord(
        int index,
        EntryType type,
        LocalDate date,
        LocalTime time,
        Map<String, Object> payload,
        Map<String, String> fieldOrigins,
        Map<String, Object> correction,
        Map<String, String> correctionOrigins,
        boolean redelivered,
        boolean cancelledDraft
) {
    public SeedRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(time, "time");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        fieldOrigins = Collections.unmodifiableMap(new LinkedHashMap<>(fieldOrigins));
        correction = correction == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(correction));
        correctionOrigins = correctionOrigins == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(correctionOrigins));
    }

    public boolean changed() {
        return correction != null;
    }

    /** Payload as it must look once the record is confirmed and the correction (if any) is applied. */
    public Map<String, Object> finalPayload() {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        if (correction != null) result.putAll(correction);
        return result;
    }

    SeedRecord withIndex(int newIndex) {
        return new SeedRecord(newIndex, type, date, time, payload, fieldOrigins, correction, correctionOrigins,
                redelivered, cancelledDraft);
    }
}
