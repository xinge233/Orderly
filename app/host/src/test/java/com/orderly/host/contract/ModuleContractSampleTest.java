package com.orderly.host.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 共享模块契约样例必须与宿主实际校验逻辑保持一致。 */
class ModuleContractSampleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path samples = Path.of("..", "..", "contracts", "samples")
            .toAbsolutePath()
            .normalize();

    @Test
    void validModuleSamplePassesRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            ModuleContract module = catalog.parseAndValidateModule(
                    objectMapper.readTree(samples.resolve("valid/module.json").toFile())
            );

            assertThat(module.moduleId()).isEqualTo("module_demo");
            assertThat(module.actions()).extracting(ActionContract::id).containsExactly("demo.read");
            assertThat(module.queries()).extracting(QueryContract::id).containsExactly("demo.list");
            assertThat(module.plannerRules()).extracting(PlannerRuleContract::id).containsExactly("demo.read");
        }
    }

    @Test
    void invalidModuleSampleFailsRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(samples.resolve("invalid/module-missing-risk.json").toFile())
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("risk");
        }
    }

    @Test
    void controlModuleSampleCarriesProtectedSystemMetadata() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            ModuleContract module = catalog.parseAndValidateModule(
                    objectMapper.readTree(samples.resolve("valid/module-control.json").toFile())
            );
            assertThat(module.moduleId()).isEqualTo("module_control");
            assertThat(module.system()).isNotNull();
            assertThat(module.system().role()).isEqualTo("MODULE_CONTROL");
            assertThat(module.system().protectedModule()).isTrue();
        }
    }

    @Test
    void invalidSystemRoleFailsRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(samples.resolve("invalid/module-system-invalid-role.json").toFile())
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("系统模块角色");
        }
    }
}
