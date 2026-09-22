package org.healthtg.core.entry;

import java.util.Objects;

public record DraftCreationResult(Entry entry, Outcome outcome) {
    public DraftCreationResult {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(outcome, "outcome");
    }

    public enum Outcome {
        CREATED,
        EXISTING_UPDATE,
        ACTIVE_DRAFT_EXISTS
    }
}
