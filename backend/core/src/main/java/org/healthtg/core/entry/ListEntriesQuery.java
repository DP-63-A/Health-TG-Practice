package org.healthtg.core.entry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public record ListEntriesQuery(OwnerContext owner, EntryStatus status, EntryType type,
                               LocalDate from, LocalDate to, ZoneId timezone) {
    public ListEntriesQuery {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(status);
        Objects.requireNonNull(timezone);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }
}
