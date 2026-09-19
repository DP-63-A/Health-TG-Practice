package com.health.analytics.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnalyticsContractTest {

    private static ObjectMapper mapper;
    private static JsonSchema schema;

    @BeforeAll
    static void setUp() throws Exception {
        mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        
        try (InputStream schemaStream = AnalyticsContractTest.class
                .getResourceAsStream("/contracts/schemas/analytics_response.json")) {
            if (schemaStream == null) {
                throw new IllegalStateException("OpenAPI schema file not found in test resources.");
            }
            JsonNode schemaNode = mapper.readTree(schemaStream);
            schema = factory.getSchema(schemaNode);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/contracts/fixtures/analytics_normal.json",
        "/contracts/fixtures/analytics_empty.json",
        "/contracts/fixtures/analytics_gaps.json",
        "/contracts/fixtures/analytics_dedup.json",
        "/contracts/fixtures/analytics_filtered.json"
    })
    @DisplayName("Проверка соответствия JSON-фикстур согласованному OpenAPI контракту")
    void testFixturesMatchContract(String fixturePath) throws Exception {
        try (InputStream fixtureStream = AnalyticsContractTest.class.getResourceAsStream(fixturePath)) {
            if (fixtureStream == null) {
                throw new IllegalArgumentException("Fixture not found at path: " + fixturePath);
            }
            
            var jsonNode = mapper.readTree(fixtureStream);
            Set<ValidationMessage> errors = schema.validate(jsonNode);

            assertTrue(errors.isEmpty(), () -> {
                var sb = new StringBuilder("Ошибка валидации фикстуры " + fixturePath + ":\n");
                for (ValidationMessage error : errors) {
                    sb.append(" - ").append(error.getMessage()).append("\n");
                }
                return sb.toString();
            });
        }
    }
}
