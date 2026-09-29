package org.healthtg.core.entry;

public final class EntryVersionConflictException extends RuntimeException {
    private final Entry current;
    public EntryVersionConflictException(Entry current) {
        super("Entry revision conflict");
        this.current = current;
    }
    public Entry current() { return current; }
}
