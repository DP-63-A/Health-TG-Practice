package org.healthtg.seed;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoEnvironmentGuardTest {
    @Test
    void acceptsOnlyExplicitDemoFlagAndFixedLoopbackDatabase() {
        DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://localhost:27017/health_tg_demo");
        DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://127.0.0.1/health_tg_demo");

        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("false", "mongodb://localhost/health_tg_demo"));
        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://localhost/health_tg"));
        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://example.com/health_tg_demo"));
        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb+srv://localhost/health_tg_demo"));
    }

    @Test
    void composeMongoRequiresExplicitComposeFlagAndFixedDatabase() {
        DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://mongo:27017/health_tg_demo", "true");

        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://mongo:27017/health_tg_demo", null));
        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://mongo:27017/health_tg", "true"));
        assertThrows(IllegalStateException.class, () ->
                DemoEnvironmentGuard.requireDemoEnvironment("true", "mongodb://example.com/health_tg_demo", "true"));
    }

    @Test
    void profileConfigurationAcceptsOnlyInternalUserUuidsAndNoPublicSelector() {
        Map<String, String> environment = Map.of(
                "BE3_05_REGULAR_USER_ID", "11111111-1111-4111-8111-111111111101",
                "BE3_05_IRREGULAR_USER_ID", "11111111-1111-4111-8111-111111111102",
                "BE3_05_INCOMPLETE_USER_ID", "11111111-1111-4111-8111-111111111103");

        DemoProfileOwners owners = DemoProfileOwners.fromEnvironment(environment);
        assertEquals(UUID.fromString(environment.get("BE3_05_REGULAR_USER_ID")),
                owners.owner(SyntheticProfile.REGULAR));
        assertThrows(IllegalArgumentException.class, () ->
                DemoProfileOwners.fromEnvironment(Map.of(
                        "BE3_05_REGULAR_USER_ID", "123456789",
                        "BE3_05_IRREGULAR_USER_ID", environment.get("BE3_05_IRREGULAR_USER_ID"),
                        "BE3_05_INCOMPLETE_USER_ID", environment.get("BE3_05_INCOMPLETE_USER_ID"))));

        assertThrows(IllegalArgumentException.class, () -> DemoDataCommand.Command.parse(
                new String[]{"seed", "--profile=regular"}));
        assertThrows(IllegalArgumentException.class, () -> DemoDataCommand.Command.parse(
                new String[]{"reset", "--profile=regular"}));
        assertTrue(DemoDataCommand.Command.parse(new String[]{"seed"}).seed()
                == SyntheticDatasetGenerator.DEFAULT_SEED);
    }
}
