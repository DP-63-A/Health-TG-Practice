package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.TelegramUpdateKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SaveDialogStateCommand(
        OwnerContext owner,
        UUID activeEntryId,
        String step,
        Map<String, Object> context,
        TelegramUpdateKey updateKey
) {
    public SaveDialogStateCommand {
        Objects.requireNonNull(owner, "owner");
        if (Objects.requireNonNull(step, "step").isBlank()) throw new IllegalArgumentException("step must not be blank");
        context = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(context, "context")));
        Objects.requireNonNull(updateKey, "updateKey");
    }
}
