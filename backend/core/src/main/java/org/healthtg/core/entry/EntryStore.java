package org.healthtg.core.entry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntryStore {
    Entry save(Entry entry);

    Optional<Entry> findById(UUID id);

    Optional<Entry> findByTelegramUpdateKey(String updateKey);

    Optional<Entry> findBySubmissionId(String submissionId);

    Optional<Entry> findActiveDraft(UUID ownerId);

    List<Entry> findByOwnerAndStatus(UUID ownerId, EntryStatus status);
}
