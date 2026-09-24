package org.healthtg.core.entry;

import java.time.Instant;
import java.util.Objects;

public record CreateCheckinCommand(
        OwnerContext owner,
        CheckinCategory category,
        int score,
        Instant occurredAt,
        TelegramUpdateKey updateKey
) {
    public CreateCheckinCommand {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(category, "category");
        if (score < 1 || score > 5) throw new IllegalArgumentException("score must be between 1 and 5");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(updateKey, "updateKey");
    }
}
