package org.healthtg.core.entry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntryCoreService {
    Entry createCheckin(CreateCheckinCommand command);

    DraftCreationResult createDraft(CreateDraftCommand command);

    Optional<Entry> findActiveDraft(OwnerContext owner);

    List<Entry> listConfirmedEntries(ListConfirmedEntriesQuery query);

    List<Entry> listEntries(ListEntriesQuery query);

    Entry requireEntry(OwnerContext owner, UUID entryId);

    Entry patch(PatchEntryCommand command);

    Entry confirm(ConfirmEntryCommand command);

    Entry cancel(OwnerContext owner, UUID entryId);

    Entry delete(OwnerContext owner, UUID entryId, long expectedRevision);
}
