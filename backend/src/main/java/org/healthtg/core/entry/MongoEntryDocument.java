package org.healthtg.core.entry;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document("entries")
@CompoundIndexes({
        @CompoundIndex(name = "owner_status_idx", def = "{'ownerId': 1, 'status': 1}"),
        @CompoundIndex(name = "owner_occurred_at_idx", def = "{'ownerId': 1, 'occurredAt': -1}"),
        @CompoundIndex(name = "one_active_draft_per_owner", def = "{'ownerId': 1}", unique = true,
                partialFilter = "{'status': 'draft'}"),
        @CompoundIndex(name = "submission_id_unique", def = "{'submissionId': 1}", unique = true,
                partialFilter = "{'submissionId': {'$type': 'string'}}")
})
record MongoEntryDocument(
        @Id String id,
        String ownerId,
        String type,
        String status,
        String sourceKind,
        Map<String, Object> sourceRef,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        long revision,
        Map<String, Object> payload,
        Map<String, String> fieldOrigins,
        String submissionId,
        @Indexed(unique = true) String telegramUpdateKey
) {
}
