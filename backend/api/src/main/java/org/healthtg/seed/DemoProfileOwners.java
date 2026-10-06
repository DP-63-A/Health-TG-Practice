package org.healthtg.seed;

import java.util.Map;
import java.util.UUID;

public record DemoProfileOwners(UUID regular, UUID irregular, UUID incomplete) {
    public static DemoProfileOwners fromEnvironment(Map<String, String> environment) {
        return new DemoProfileOwners(requiredUuid(environment, "BE3_05_REGULAR_USER_ID"),
                requiredUuid(environment, "BE3_05_IRREGULAR_USER_ID"),
                requiredUuid(environment, "BE3_05_INCOMPLETE_USER_ID"));
    }

    public UUID owner(SyntheticProfile profile) {
        return switch (profile) {
            case REGULAR -> regular;
            case IRREGULAR -> irregular;
            case INCOMPLETE -> incomplete;
        };
    }

    private static UUID requiredUuid(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required demo account UUID: " + key);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " must be an existing account UUID", exception);
        }
    }
}
