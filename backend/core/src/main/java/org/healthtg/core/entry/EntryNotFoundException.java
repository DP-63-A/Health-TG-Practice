package org.healthtg.core.entry;

public final class EntryNotFoundException extends RuntimeException {
    public EntryNotFoundException() { super("Entry not found"); }
}
