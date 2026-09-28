package org.healthtg.core.entry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

public record ListConfirmedEntriesQuery(
        OwnerContext owner,
        LocalDate from,
        LocalDate to,
        ZoneId timezone,
        Set<EntryType> types
) {
    public ListConfirmedEntriesQuery {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(timezone, "timezone");
        types = Set.copyOf(Objects.requireNonNull(types, "types"));
        if (to.isBefore(from)) throw new IllegalArgumentException("to precedes from");
    }
}
