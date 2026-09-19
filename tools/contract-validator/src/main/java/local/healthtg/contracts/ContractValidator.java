package local.healthtg.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Validates OpenAPI documents and positive/negative JSON examples against schemas.
 * Run from repo root: {@code ./gradlew validateContracts} or
 * {@code ./gradlew :contract-validator:validate}.
 */
public final class ContractValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String SCHEMA_ID_PREFIX = "https://health-tg.local/schemas/";

    private final Path contractsRoot;
    private final Path schemasDir;
    private final JsonSchemaFactory schemaFactory;
    private final SchemaValidatorsConfig schemaConfig;

    public ContractValidator(Path contractsRoot) {
        this.contractsRoot = contractsRoot.toAbsolutePath().normalize();
        this.schemasDir = this.contractsRoot.resolve("schemas");
        String localPrefix = this.schemasDir.toUri().toString();
        if (!localPrefix.endsWith("/")) {
            localPrefix = localPrefix + "/";
        }
        final String mappedPrefix = localPrefix;
        this.schemaFactory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers ->
                        mappers.mapPrefix(SCHEMA_ID_PREFIX, mappedPrefix))
        );
        this.schemaConfig = SchemaValidatorsConfig.builder().build();
    }

    public static void main(String[] args) throws Exception {
        Path root = resolveContractsRoot(args);
        ContractValidator validator = new ContractValidator(root);
        List<String> failures = validator.validateAll();
        if (failures.isEmpty()) {
            System.out.println("OK: OpenAPI + examples validated under " + root);
            return;
        }
        System.err.println("FAILED (" + failures.size() + "):");
        failures.forEach(f -> System.err.println(" - " + f));
        System.exit(1);
    }

    static Path resolveContractsRoot(String[] args) {
        if (args.length > 0) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("contracts"),
                cwd.resolve("..").resolve("..").resolve("contracts"),
                cwd.getParent() != null ? cwd.getParent().resolve("contracts") : null
        };
        for (Path c : candidates) {
            if (c != null && Files.isDirectory(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        return cwd.resolve("contracts").toAbsolutePath().normalize();
    }

    public List<String> validateAll() throws IOException {
        List<String> failures = new ArrayList<>();
        failures.addAll(validateOpenApi(contractsRoot.resolve("openapi.yaml")));
        failures.addAll(validateOpenApi(contractsRoot.resolve("internal.yaml")));
        failures.addAll(validateValidExamples());
        failures.addAll(validateInvalidExamples());
        return failures;
    }

    List<String> validateOpenApi(Path yaml) {
        List<String> failures = new ArrayList<>();
        if (!Files.isRegularFile(yaml)) {
            failures.add("Missing OpenAPI file: " + yaml);
            return failures;
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result =
                new OpenAPIV3Parser().readLocation(yaml.toUri().toString(), null, options);
        if (result.getMessages() != null) {
            for (String message : result.getMessages()) {
                if (message == null) {
                    continue;
                }
                String lower = message.toLowerCase();
                boolean hardFailure = lower.startsWith("unable")
                        || lower.contains("unable to read")
                        || lower.contains("not found")
                        || lower.contains("attribute ") && lower.contains("is not of type")
                        || (lower.contains("error") && !lower.contains("error-"));
                if (hardFailure) {
                    failures.add(yaml.getFileName() + ": " + message);
                }
            }
        }
        if (result.getOpenAPI() == null) {
            failures.add(yaml.getFileName() + ": parse returned null OpenAPI");
            if (result.getMessages() != null) {
                result.getMessages().forEach(m -> failures.add(yaml.getFileName() + ": " + m));
            }
        } else if (result.getOpenAPI().getPaths() == null || result.getOpenAPI().getPaths().isEmpty()) {
            failures.add(yaml.getFileName() + ": paths missing or empty");
        }
        try {
            validateExternalExamples(YAML_MAPPER.readTree(yaml.toFile()), yaml.getParent(), failures);
        } catch (IOException e) {
            failures.add(yaml.getFileName() + ": cannot read examples: " + e.getMessage());
        }
        return failures;
    }

    private void validateExternalExamples(JsonNode node, Path base, List<String> failures) {
        if (node.isObject()) {
            JsonNode examples = node.get("examples");
            if (examples != null && examples.isObject()) {
                examples.fields().forEachRemaining(entry -> {
                    JsonNode externalValue = entry.getValue().get("externalValue");
                    if (externalValue == null) {
                        return;
                    }
                    if (!externalValue.isTextual()) {
                        failures.add("Example " + entry.getKey() + ": externalValue must be a URI");
                        return;
                    }
                    Path example = base.resolve(externalValue.textValue()).normalize();
                    if (!example.startsWith(contractsRoot) || !Files.isRegularFile(example)) {
                        failures.add("Example " + entry.getKey() + ": missing local JSON " + example);
                        return;
                    }
                    try {
                        MAPPER.readTree(example.toFile());
                    } catch (IOException e) {
                        failures.add("Example " + entry.getKey() + ": invalid JSON " + e.getMessage());
                    }
                });
            }
            node.elements().forEachRemaining(child -> validateExternalExamples(child, base, failures));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                validateExternalExamples(child, base, failures);
            }
        }
    }

    private List<String> validateValidExamples() throws IOException {
        List<String> failures = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("user-me.json", "user.json");
        mapping.put("auth-session.json", "auth.json#/$defs/TelegramAuthResponse");
        mapping.put("entry-meal.json", "entry.json");
        mapping.put("entry-draft-meal.json", "entry.json");
        mapping.put("entry-metrics.json", "entry.json");
        mapping.put("entry-metrics-zero-steps.json", "entry.json");
        mapping.put("entry-checkin.json", "entry.json");
        mapping.put("entry-note.json", "entry.json");
        mapping.put("entries-empty.json", "auth.json#/$defs/EntryListResponse");
        mapping.put("entries-list-confirmed.json", "auth.json#/$defs/EntryListResponse");
        mapping.put("error-version-conflict.json", "error.json");
        mapping.put("error-validation.json", "error.json");
        mapping.put("analytics-days7.json", "analytics.json");

        Path validDir = contractsRoot.resolve("examples/valid");
        try (Stream<Path> stream = Files.list(validDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !mapping.containsKey(name))
                    .forEach(name -> failures.add("Unvalidated valid example: " + name));
        }
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            Path example = validDir.resolve(e.getKey());
            if (!Files.isRegularFile(example)) {
                failures.add("Missing valid example: " + e.getKey());
                continue;
            }
            Set<ValidationMessage> errors = validateAgainst(e.getValue(), example, false);
            if (!errors.isEmpty()) {
                failures.add("valid/" + e.getKey() + " should pass but failed: " + errors);
            }
        }
        return failures;
    }

    private List<String> validateInvalidExamples() throws IOException {
        List<String> failures = new ArrayList<>();
        Path invalidDir = contractsRoot.resolve("examples/invalid");
        Set<String> required = Set.of(
                "entry-unknown-status.json", "entry-unknown-type.json",
                "entry-score-out-of-range.json", "entry-negative-steps.json",
                "entry-negative-sleep.json");
        if (!Files.isDirectory(invalidDir)) {
            failures.add("Missing invalid examples directory");
            return failures;
        }
        try (Stream<Path> stream = Files.list(invalidDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                failures.add("No invalid JSON examples found");
            }
            for (String name : required) {
                if (!Files.isRegularFile(invalidDir.resolve(name))) {
                    failures.add("Missing invalid example: " + name);
                }
            }
            for (Path example : files) {
                JsonNode rejection = MAPPER.readTree(example.toFile()).path("_rejection");
                String reason = rejection.path("reason").asText("");
                String field = rejection.path("field").asText("");
                String keyword = rejection.path("keyword").asText("");
                if (reason.isBlank() || field.isBlank() || keyword.isBlank()) {
                    failures.add("invalid/" + example.getFileName()
                            + " needs _rejection.reason, field and keyword");
                    continue;
                }
                Set<ValidationMessage> errors = validateAgainst("entry.json", example, true);
                if (errors.isEmpty()) {
                    failures.add("invalid/" + example.getFileName()
                            + " should be rejected by schema but passed");
                } else if (errors.stream().noneMatch(error ->
                        ("/" + field.replace('.', '/')).equals(error.getInstanceLocation().toString())
                                && keyword.equals(error.getType()))) {
                    failures.add("invalid/" + example.getFileName()
                            + " was not rejected for " + field + " (" + keyword + "): " + errors);
                }
            }
        }
        return failures;
    }

    private Set<ValidationMessage> validateAgainst(
            String schemaRef, Path example, boolean stripRejection) throws IOException {
        String refUri = SCHEMA_ID_PREFIX + schemaRef;
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("$ref", refUri);
        JsonSchema schema = schemaFactory.getSchema(schemasDir.toUri(), wrapper, schemaConfig);

        JsonNode instance = MAPPER.readTree(example.toFile());
        if (stripRejection && instance instanceof ObjectNode obj) {
            obj.remove("_rejection");
        }
        return schema.validate(instance);
    }
}
