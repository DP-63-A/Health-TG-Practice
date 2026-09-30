package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.TelegramUpdateKey;

import java.util.Optional;
import java.util.UUID;

public interface DialogStateService {
    DialogState save(SaveDialogStateCommand command);

    Optional<DialogState> find(OwnerContext owner);

    boolean clearIfCurrent(OwnerContext owner, UUID activeEntryId, long expectedRevision,
                           TelegramUpdateKey updateKey);
}
