package org.healthtg.core.entry;

import java.util.Objects;

public record TelegramUpdateKey(String botKey, long updateId) {
    public TelegramUpdateKey {
        if (Objects.requireNonNull(botKey, "botKey").isBlank()) {
            throw new IllegalArgumentException("botKey must not be blank");
        }
        if (updateId < 0) {
            throw new IllegalArgumentException("updateId must be non-negative");
        }
    }

    public String storageKey() {
        return botKey + ":" + updateId;
    }
}
