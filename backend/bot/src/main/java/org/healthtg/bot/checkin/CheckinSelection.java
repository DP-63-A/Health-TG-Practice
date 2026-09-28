package org.healthtg.bot.checkin;

import java.util.Objects;
import java.util.UUID;

/** A user's choice, not a persisted entry or a promise of successful storage. */
public record CheckinSelection(UUID selectionId, long owner, CheckinCategory category, int score) {
    public CheckinSelection {
        Objects.requireNonNull(selectionId, "selectionId");
        Objects.requireNonNull(category, "category");
        if (owner <= 0 || score < 1 || score > 5) {
            throw new IllegalArgumentException("Invalid checkin selection");
        }
    }
}
