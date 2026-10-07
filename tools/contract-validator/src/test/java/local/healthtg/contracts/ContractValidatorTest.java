package local.healthtg.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir Path tempDir;

    @Test
    void openApiAndExamplesPassContractChecks() throws Exception {
        Path contracts = resolveContractsRoot();
        assertTrue(
                Files.isRegularFile(contracts.resolve("openapi.yaml")),
                "openapi.yaml not found under " + contracts
        );

        ContractValidator validator = new ContractValidator(contracts);
        List<String> failures = validator.validateAll();
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test void missingOpenApiVersionIsReported() throws Exception {
        Path contracts = copyContracts();
        Path openapi = contracts.resolve("openapi.yaml");
        ObjectNode document = (ObjectNode) YAML.readTree(openapi.toFile());
        ((ObjectNode) document.path("info")).remove("version");
        YAML.writeValue(openapi.toFile(), document);
        List<String> failures = new ContractValidator(contracts).validateOpenApi(openapi);
        assertFalse(failures.isEmpty());
        assertTrue(failures.stream().anyMatch(f -> f.contains("version")), () -> String.join("\n", failures));
    }

    @Test void invalidUuidAndDateTimeAreRejected() throws Exception {
        Path contracts = copyContracts();
        Path meal = contracts.resolve("examples/valid/entry-meal.json");
        ObjectNode original = (ObjectNode) JSON.readTree(meal.toFile());
        original.put("id", "not-a-uuid");
        JSON.writeValue(meal.toFile(), original);
        List<String> uuidFailures = new ContractValidator(contracts).validateAll();
        assertTrue(uuidFailures.stream().anyMatch(f -> f.contains("entry-meal.json") && f.contains("uuid")),
                () -> String.join("\n", uuidFailures));

        original.put("id", "22222222-2222-4222-8222-222222222201");
        original.put("occurred_at", "not-a-date");
        JSON.writeValue(meal.toFile(), original);
        List<String> dateFailures = new ContractValidator(contracts).validateAll();
        assertTrue(dateFailures.stream().anyMatch(f -> f.contains("entry-meal.json") && f.contains("date-time")),
                () -> String.join("\n", dateFailures));
    }

    @Test void inlineAuthExampleMustMatchRequestSchema() throws Exception {
        Path contracts = copyContracts();
        Path openapi = contracts.resolve("openapi.yaml");
        Files.writeString(openapi, Files.readString(openapi).replace(
                "                  init_data: \"query_id=AAH...&user=%7B%22id%22%3A1%7D&auth_date=1710000000&hash=...\"",
                "                  wrong_field: 123"));
        List<String> failures = new ContractValidator(contracts).validateOpenApi(openapi);
        assertTrue(failures.stream().anyMatch(f -> f.contains("sample") && f.contains("init_data")),
                () -> String.join("\n", failures));
    }

    @Test void negativePulseViolatesSchemaWhileZeroIsValid() throws Exception {
        Path contracts = copyContracts();
        Path path = contracts.resolve("examples/valid/entry-metrics.json");
        // Use a complete existing Entry so this test isolates the metric value boundary.
        Path source = contracts.resolve("examples/valid/entry-meal.json");
        ObjectNode entry = (ObjectNode) JSON.readTree(source.toFile());
        entry.put("type", "metrics");
        ObjectNode payload = entry.putObject("payload");
        payload.put("code", "heart_rate"); payload.put("value", 0);
        payload.put("unit", "bpm"); payload.put("local_date", "2026-10-06");
        JSON.writeValue(path.toFile(), entry);
        assertTrue(new ContractValidator(contracts).validateAll().isEmpty());
        for (String negative : java.util.List.of("-1", "-0.00001")) {
            payload.put("value", new java.math.BigDecimal(negative));
            JSON.writeValue(path.toFile(), entry);
            var failures = new ContractValidator(contracts).validateAll();
            assertTrue(failures.stream().anyMatch(f -> f.contains("entry-metrics.json")), () -> negative + ": " + String.join("\n", failures));
        }
    }

    private Path copyContracts() throws IOException {
        Path source = resolveContractsRoot();
        Path target = tempDir.resolve("contracts");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(dest);
                else Files.copy(path, dest);
            }
        }
        return target;
    }

    private static Path resolveContractsRoot() {
        String prop = System.getProperty("contracts.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        return ContractValidator.resolveContractsRoot(new String[0]);
    }
}
