package com.orderly.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractValidatorTest {
    private static final Path CONTRACTS = Path.of("..", "contracts");

    @Test
    void acceptsValidSample() throws Exception {
        ContractValidator validator = ContractValidator.load(CONTRACTS);
        JsonNode sample = validator.read(CONTRACTS.resolve("samples/valid/move-items.json"));

        validator.validateModule(sample);
    }

    @Test
    void rejectsInvalidSamplesAtTheRelevantField() throws Exception {
        ContractValidator validator = ContractValidator.load(CONTRACTS);
        Map<String, List<String>> expectedPointers = Map.of(
                "missing-risk.json", List.of("/risk"),
                "type-error.json", List.of("/name"),
                "extra-field.json", List.of("/confirmCount"),
                "invalid-risk.json", List.of("/risk"),
                "irreversible-risk-conflict.json", List.of("/risk", "/inverse", "/irreversible"),
                "inverse-null-reversible.json", List.of("/inverse", "/irreversible")
        );

        for (Map.Entry<String, List<String>> entry : expectedPointers.entrySet()) {
            JsonNode sample = validator.read(CONTRACTS.resolve("samples/invalid").resolve(entry.getKey()));
            assertThatThrownBy(() -> validator.validateModule(sample))
                    .isInstanceOfSatisfying(ContractValidationException.class,
                            exception -> assertThat(exception.violations())
                                    .as(entry.getKey())
                                    .anyMatch(message -> entry.getValue().stream().anyMatch(message::contains)));
        }
    }

    @Test
    void acceptsEmptyRegistry() throws Exception {
        ContractValidator validator = ContractValidator.load(CONTRACTS);
        JsonNode registry = validator.read(CONTRACTS.resolve("registry.json"));

        assertThat(validator.validateRegistry(registry)).isEmpty();
    }
}
