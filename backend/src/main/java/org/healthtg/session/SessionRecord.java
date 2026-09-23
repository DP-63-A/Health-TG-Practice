package org.healthtg.session;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
}
