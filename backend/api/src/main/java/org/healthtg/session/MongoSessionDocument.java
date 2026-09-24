package org.healthtg.session;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("sessions")
public record MongoSessionDocument(
        @Id String tokenHash,
        String userId,
        Instant createdAt,
        @Indexed(expireAfter = "0s") Instant expiresAt
) {
}
