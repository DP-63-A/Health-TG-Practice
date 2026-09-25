package org.healthtg.bot;

import java.net.URI;
import java.util.Set;

/** Explicit configuration supplied by the launcher, never inferred from message contents. */
public record BotSettings(Set<Long> allowedUserIds, String botUsername, URI miniAppUrl) {
    public BotSettings {
        allowedUserIds = Set.copyOf(allowedUserIds);
        if (allowedUserIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Allowlist must contain positive user IDs");
        }
        if (botUsername == null || !botUsername.matches("[A-Za-z0-9_]{5,32}")) {
            throw new IllegalArgumentException("Bot username must contain 5–32 letters, digits or underscores");
        }
        if (miniAppUrl != null && (!"https".equalsIgnoreCase(miniAppUrl.getScheme())
                || miniAppUrl.getHost() == null || miniAppUrl.getUserInfo() != null)) {
            throw new IllegalArgumentException("Mini App URL must be an absolute HTTPS URL without credentials");
        }
    }

    @Override public String toString() {
        return "BotSettings[allowlist=<redacted>, username=<redacted>, miniAppConfigured="
                + (miniAppUrl != null) + "]";
    }
}
