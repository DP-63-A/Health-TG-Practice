package org.healthtg.core.entry;

import java.util.Objects;
import java.util.UUID;

public record OwnerContext(UUID userId) {
    public OwnerContext {
        Objects.requireNonNull(userId, "userId");
    }
}
