package org.healthtg.core.entry;

public enum SourceKind {
    TEXT("text"),
    FOOD_PHOTO("food_photo"),
    HEALTH_SCREENSHOT("health_screenshot"),
    WATCH_PHOTO("watch_photo"),
    QUICK_CHECKIN("quick_checkin"),
    SEED("seed");

    private final String code;

    SourceKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SourceKind fromCode(String code) {
        for (SourceKind value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("Unknown source kind");
    }
}
