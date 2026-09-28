package org.healthtg.bot.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads the shared schemas in place: no copied model and no connection to health-tg.local. */
class TextPayloadContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SCHEMAS = Path.of(System.getProperty("contracts.root")).resolve("schemas");
    private final TextInputParser parser = new TextInputParser();

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 за день прошла 8000 шагов",
            "29.02.2024 сон за день: 7 ч 10 мин",
            "25.09.2026 в 10:00 пульс в покое 68 уд/мин",
            "25.09.2026 еда: паста",
            "25.09.2026 съела 0,125 кг пасты",
            "После завтрака чувствую себя хорошо",
            "😀"
    })
    void supportedPayloadsMatchRealProjectSchemasAndEnums(String input) throws Exception {
        var result = parser.parse(input);
        assertTrue(result.outcome() == PARSED || result.outcome() == NOTE_SUGGESTED, result.toString());
        assertPayloadValid(result);
    }

    @ParameterizedTest @ValueSource(strings = {"Я спала семь часов", "Пульс 70", "За день прошла 8000 шагов"})
    void knownMetricDataWithUnknownDateAndUnitMayStillMatchDraftPayloadSchema(String input) throws Exception {
        var result = parser.parse(input);
        assertEquals(NEEDS_CLARIFICATION, result.outcome());
        assertPayloadValid(result);
    }

    @Test void realSchemaReferencesAndFormatValidationAreActive() throws Exception {
        var result = parser.parse("25.09.2026 за день прошла 8000 шагов");
        JsonSchema schema = schema("payloads/metrics.json");
        ObjectNode payload = JSON.valueToTree(result.data().payload());
        assertTrue(schema.validate(payload).isEmpty());
        payload.put("code", "invented_metric");
        assertFalse(schema.validate(payload).isEmpty(), "common.json MetricCode reference must be enforced");
        payload.put("code", "steps");
        payload.put("local_date", "2026-02-31");
        assertFalse(schema.validate(payload).isEmpty(), "format assertion must validate real dates");
        payload.put("local_date", "2026-09-25");
        payload.put("value", -1);
        assertFalse(schema.validate(payload).isEmpty(), "negative steps must violate conditional schema");
    }

    private static void assertPayloadValid(TextParseResult result) throws Exception {
        assertNotNull(result.data());
        var data = result.data();
        var errors = schema("payloads/" + data.type() + ".json").validate(JSON.valueToTree(data.payload()));
        assertTrue(errors.isEmpty(), () -> result + " -> " + errors);
        var common = JSON.readTree(Files.readString(SCHEMAS.resolve("common.json"))).path("$defs");
        assertTrue(contains(common.path("EntryType").path("enum"), data.type()));
        assertTrue(contains(common.path("SourceKind").path("enum"), data.sourceKind()));
        data.fieldOrigins().forEach((key, origin) -> {
            assertTrue(data.payload().containsKey(key), "origin may only refer to a known field: " + key);
            assertTrue(contains(common.path("FieldOrigin").path("enum"), origin), origin);
        });
    }

    private static boolean contains(com.fasterxml.jackson.databind.JsonNode array, String text) {
        for (var value : array) if (value.asText().equals(text)) return true;
        return false;
    }

    private static JsonSchema schema(String relative) throws Exception {
        Path file = SCHEMAS.resolve(relative);
        assertTrue(Files.isRegularFile(file), file.toString());
        String prefix = SCHEMAS.toAbsolutePath().normalize().toUri().toString();
        String localPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix("https://health-tg.local/schemas/", localPrefix)));
        return factory.getSchema(file.toUri(), JSON.readTree(Files.readString(file)),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }
}
