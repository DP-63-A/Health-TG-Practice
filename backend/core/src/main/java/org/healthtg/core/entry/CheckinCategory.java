package org.healthtg.core.entry;

public enum CheckinCategory {
    SLEEP_QUALITY("sleep_quality"),
    DIGESTION_COMFORT("digestion_comfort"),
    WELLBEING("wellbeing"),
    MOOD("mood");

    private final String code;

    CheckinCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CheckinCategory fromCode(String code) {
        for (CheckinCategory value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("Unknown check-in category");
    }
}
