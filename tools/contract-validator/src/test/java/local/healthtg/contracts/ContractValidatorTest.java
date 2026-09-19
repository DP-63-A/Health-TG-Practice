package local.healthtg.contracts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractValidatorTest {

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

    private static Path resolveContractsRoot() {
        String prop = System.getProperty("contracts.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        return ContractValidator.resolveContractsRoot(new String[0]);
    }
}
