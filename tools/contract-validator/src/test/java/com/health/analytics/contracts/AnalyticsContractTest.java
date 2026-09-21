package com.health.analytics.contracts;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("contracts/fixtures");

    @Test
    void expectedNumbersAreIndependent() throws Exception {
        JsonNode normal = read("analytics_expected_normal.json");
        assertEquals(930, normal.at("/cards/nutrition/energy_kcal").asDouble());
        assertEquals(900, normal.at("/cards/sleep/total_minutes").asInt());
        assertEquals(450, normal.at("/cards/sleep/average_minutes").asDouble());
        assertEquals(5000, normal.at("/cards/steps/total").asInt());
        assertEquals(2, normal.at("/cards/sleep/days_with_data").asInt());
        assertEquals(5000, read("analytics_expected_dedup.json").at("/cards/steps/total").asInt());
        assertEquals(600, read("analytics_expected_filtered.json").at("/cards/nutrition/energy_kcal").asInt());
    }

    @Test
    void nullIsNotZeroAndSourcesAreNavigable() throws Exception {
        JsonNode empty = read("analytics_expected_empty.json");
        assertTrue(empty.at("/cards/nutrition/energy_kcal").isNull());
        assertTrue(empty.at("/cards/steps/total").isNull());
        assertEquals(0, empty.at("/observations/days_with_any_data").asInt());
        assertTrue(empty.at("/sources").isEmpty());
        assertEquals("mood", empty.at("/series/checkin/category").asText());
    }

    @Test
    void allExpectedSnapshotsMatchAnalyticsSchema() throws Exception {
        Path schemaFile = Path.of("contracts/schemas/analytics.json");
        var config = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build();
        var factory = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012
        );
        var schema = factory.getSchema(
            schemaFile.toUri(),
            JSON.readTree(Files.readString(schemaFile)),
            config
        );

        for (String file : new String[]{
            "analytics_expected_normal.json",
            "analytics_expected_empty.json",
            "analytics_expected_gaps.json",
            "analytics_expected_dedup.json",
            "analytics_expected_filtered.json"
        }) {
        JsonNode fixture = read(file);
        var errors = schema.validate(fixture);
        assertTrue(errors.isEmpty(), () -> file + ": " + errors);
    }
}

    private static JsonNode read(String name) throws Exception {
        return JSON.readTree(Files.readString(FIXTURES.resolve(name)));
    }
}
