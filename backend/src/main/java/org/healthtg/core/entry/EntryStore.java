package org.healthtg.core.entry;

import java.util.Optional;
import java.util.UUID;

public interface EntryStore {
    Entry save(Entry entry);

    Optional<Entry> findByTelegramUpdateKey(String updateKey);

    Optional<Entry> findBySubmissionId(String submissionId);

    Optional<Entry> findActiveDraft(UUID ownerId);
}
