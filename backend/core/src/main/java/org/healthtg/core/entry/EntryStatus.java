package org.healthtg.core.entry;

public enum EntryStatus {
    DRAFT("draft"),
    CONFIRMED("confirmed"),
    CANCELLED("cancelled"),
    DELETED("deleted");

    private final String code;

    EntryStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static EntryStatus fromCode(String code) {
        for (EntryStatus value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("Unknown entry status");
    }
}
