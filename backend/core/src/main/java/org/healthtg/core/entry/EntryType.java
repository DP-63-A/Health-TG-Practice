package org.healthtg.core.entry;

public enum EntryType {
    MEAL("meal"),
    METRICS("metrics"),
    CHECKIN("checkin"),
    NOTE("note");

    private final String code;

    EntryType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static EntryType fromCode(String code) {
        for (EntryType value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("Unknown entry type");
    }
}
