package org.healthtg.core.entry;

public final class EntryStatusConflictException extends RuntimeException {
    public EntryStatusConflictException() { super("Invalid entry status transition"); }
}
