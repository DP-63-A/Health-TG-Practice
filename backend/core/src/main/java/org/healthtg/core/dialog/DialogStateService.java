package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;

import java.util.Optional;

public interface DialogStateService {
    DialogState save(SaveDialogStateCommand command);

    Optional<DialogState> find(OwnerContext owner);
}
