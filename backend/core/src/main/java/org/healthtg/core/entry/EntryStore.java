package org.healthtg.core.entry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntryStore {
    /** Includes every owner and status to protect legacy or inconsistent references. */
    boolean hasFileReference(UUID fileId);
    Entry save(Entry entry);

    Optional<Entry> findById(UUID id);

    Optional<Entry> findByTelegramUpdateKey(String updateKey);

    Optional<Entry> findBySubmissionId(String submissionId);

    Optional<Entry> findActiveDraft(UUID ownerId);

    List<Entry> findByOwnerAndStatus(UUID ownerId, EntryStatus status);

    Optional<Entry> replaceIfCurrent(Entry current, Entry replacement);
}
