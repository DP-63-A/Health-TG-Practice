package org.healthtg.core.dialog;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document("dialog_states")
record MongoDialogStateDocument(
        @Id String ownerId,
        String activeEntryId,
        String step,
        Map<String, Object> context,
        long revision,
        Instant updatedAt,
        String telegramUpdateKey,
        @Version Long mongoVersion
) {
}
