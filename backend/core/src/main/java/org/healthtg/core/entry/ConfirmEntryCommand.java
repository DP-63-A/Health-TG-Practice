package org.healthtg.core.entry;

import java.util.Objects;
import java.util.UUID;

public record ConfirmEntryCommand(OwnerContext owner, UUID entryId, String submissionId,
                                  long expectedRevision) {
    public ConfirmEntryCommand {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(entryId);
        if (submissionId == null || submissionId.isBlank() || submissionId.length() > 128) {
            throw new IllegalArgumentException("submissionId must contain 1 to 128 characters");
        }
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be positive");
    }
}
