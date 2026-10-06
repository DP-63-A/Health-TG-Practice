package org.healthtg.seed;

public enum SyntheticProfile {
    REGULAR("regular"),
    IRREGULAR("irregular"),
    INCOMPLETE("incomplete");

    private final String code;

    SyntheticProfile(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
