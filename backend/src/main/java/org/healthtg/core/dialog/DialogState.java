package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DialogState(
        UUID ownerId,
        UUID activeEntryId,
        String step,
        Map<String, Object> context,
        long revision,
        Instant updatedAt,
        String telegramUpdateKey
) {
    public DialogState {
        Objects.requireNonNull(ownerId, "ownerId");
        if (Objects.requireNonNull(step, "step").isBlank()) throw new IllegalArgumentException("step must not be blank");
        context = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(context, "context")));
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(telegramUpdateKey, "telegramUpdateKey");
    }

    public OwnerContext owner() {
        return new OwnerContext(ownerId);
    }
}
