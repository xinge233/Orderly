package com.orderly.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Component
public final class ModuleRegistry {
    private final List<JsonNode> modules;

    public ModuleRegistry(@Value("${orderly.contracts-root:../contracts}") String contractsRoot) {
        try {
            Path root = Path.of(contractsRoot).toAbsolutePath().normalize();
            ContractValidator validator = ContractValidator.load(root);
            JsonNode registry = validator.read(root.resolve("registry.json"));
            this.modules = validator.validateRegistry(registry);
        } catch (IOException | ContractValidationException exception) {
            throw new IllegalStateException("Unable to load module registry", exception);
        }
    }

    public int moduleCount() {
        return modules.size();
    }

    public List<JsonNode> modules() {
        return modules;
    }
}
