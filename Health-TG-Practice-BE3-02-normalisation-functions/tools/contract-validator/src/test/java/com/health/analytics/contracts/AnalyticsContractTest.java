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

    private static final String SCHEMA_ID_PREFIX =
        "https://health-tg.local/schemas/";

    private static JsonSchemaFactory schemaFactory() {
        Path schemasDir = Path.of("contracts/schemas")
                .toAbsolutePath()
                .normalize();

        String localPrefix = schemasDir.toUri().toString();
        if (!localPrefix.endsWith("/")) {
            localPrefix += "/";
        }

        String mappedPrefix = localPrefix;

        return JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers ->
                        mappers.mapPrefix(SCHEMA_ID_PREFIX, mappedPrefix))
        );
    }

    @Test
    void expectedNumbersAreIndependent() throws Exception {
        JsonNode normal = read("analytics_expected_normal.json");
        assertEquals(930, normal.at("/cards/nutrition/energy_kcal").asDouble());
        assertEquals(900, normal.at("/cards/sleep/total_minutes").asInt());
        assertEquals(450, normal.at("/cards/sleep/average_minutes").asDouble());
        assertEquals(5000, normal.at("/cards/steps/total").asInt());
        assertEquals(2, normal.at("/cards/sleep/days_with_data").asInt());
        assertEquals(5000, read("analytics_expected_dedup.json").at("/cards/steps/total").asInt());
        assertEquals(300, read("analytics_expected_filtered.json")
                .at("/cards/nutrition/energy_kcal").asInt());
        assertEquals(847.5, read("analytics_expected_mass_changed.json")
                .at("/energy_kcal").asDouble());
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
        var factory = schemaFactory();
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

    @Test
    void schemaRejectsMismatchedUnitsAndMissingSources() throws Exception {
        JsonNode normal = read("analytics_expected_normal.json");

        JsonNode wrongSleepUnit = normal.deepCopy();
        wrongSleepUnit.at("/series/sleep/0/unit").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongSleepUnit.at("/series/sleep/0")).put("unit", "count");

        JsonNode missingSleepSource = normal.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingSleepSource.at("/series/sleep/0")).putNull("source");

        JsonNode wrongCheckinSource = normal.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongCheckinSource.at("/series/checkin/points/0")).putNull("source");

        Path schemaFile = Path.of("contracts/schemas/analytics.json");
        var schema = schemaFactory()
                .getSchema(schemaFile.toUri(), JSON.readTree(Files.readString(schemaFile)),
                        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());

        assertFalse(schema.validate(wrongSleepUnit).isEmpty());
        assertFalse(schema.validate(missingSleepSource).isEmpty());
        assertFalse(schema.validate(wrongCheckinSource).isEmpty());
    }

    @Test
    void acceptedZoneIdsIncludeUtcAndOffsets() throws Exception {
        JsonNode normal = read("analytics_expected_normal.json");
        Path schemaFile = Path.of("contracts/schemas/analytics.json");
        var schema = schemaFactory()
                .getSchema(schemaFile.toUri(), JSON.readTree(Files.readString(schemaFile)),
                        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        for (String zone : new String[]{"America/Port-au-Prince", "Etc/GMT+3", "UTC"}) {
            JsonNode candidate = normal.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.at("/period")).put("timezone", zone);
            assertTrue(schema.validate(candidate).isEmpty(), zone);
        }
    }

    private static JsonNode read(String name) throws Exception {
        return JSON.readTree(Files.readString(FIXTURES.resolve(name)));
    }
}
