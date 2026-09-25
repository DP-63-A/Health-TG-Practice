package org.healthtg.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record MongoUserDocument(
        @Id String id,
        @Indexed(unique = true) long telegramId,
        String timezone,
        boolean standAccess
) {
}
