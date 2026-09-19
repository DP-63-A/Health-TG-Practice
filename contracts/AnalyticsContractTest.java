package com.health.analytics.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnalyticsContractTest {

    private static ObjectMapper jsonMapper;
    private static JsonSchema schema;

    @BeforeAll
    static void setUp() throws Exception {
        jsonMapper = new ObjectMapper();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

        try (InputStream openApiStream = AnalyticsContractTest.class
                .getResourceAsStream("/contracts/openapi.yaml")) {
            
            assertNotNull(openApiStream, "Файл contracts/openapi.yaml не найден в test resources!");

            JsonNode openApiNode = yamlMapper.readTree(openApiStream);
            JsonNode analyticsSchemaNode = openApiNode
                    .path("components")
                    .path("schemas")
                    .path("AnalyticsResponse");

            schema = factory.getSchema(analyticsSchemaNode);
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
            assertNotNull(fixtureStream, "Фикстура не найдена по пути: " + fixturePath);

            var jsonNode = jsonMapper.readTree(fixtureStream);
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
