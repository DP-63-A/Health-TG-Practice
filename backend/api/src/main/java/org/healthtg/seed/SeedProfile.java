package org.healthtg.seed;

public enum SeedProfile {
    REGULAR("regular"),
    IRREGULAR("irregular"),
    INCOMPLETE("incomplete");

    private final String code;

    SeedProfile(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
