package org.healthtg.core.entry;

import java.util.List;
import java.util.Optional;

public interface EntryCoreService {
    Entry createCheckin(CreateCheckinCommand command);

    DraftCreationResult createDraft(CreateDraftCommand command);

    Optional<Entry> findActiveDraft(OwnerContext owner);

    List<Entry> listConfirmedEntries(ListConfirmedEntriesQuery query);
}
