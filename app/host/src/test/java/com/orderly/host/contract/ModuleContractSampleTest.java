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
            assertThat(module.actions().getFirst().outputSchema()).isNotNull();
            assertThat(module.queries()).extracting(QueryContract::id).containsExactly("demo.list");
            assertThat(module.queries().getFirst().outputSchema()).isNotNull();
            assertThat(module.queries().getFirst().display()).isNotNull();
            assertThat(module.queries().getFirst().display().fallbackLayout())
                    .isEqualTo("list");
            assertThat(module.queries().getFirst().display().fields())
                    .extracting(QueryDisplayContract.Field::label)
                    .containsExactly("标题");
            assertThat(module.queries().getFirst().display().detailFields())
                    .extracting(QueryDisplayContract.Field::key)
                    .containsExactly("status");
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
    void invalidQueryDisplayFailsRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve("invalid/module-query-display-invalid.json").toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fallbackLayout");
        }
    }

    @Test
    void deprecatedStaticAiCallableFailsRuntimeValidationAndSchemaValidation() throws Exception {
        var schema = objectMapper.readTree(
                samples.getParent().resolve("module.schema.json").toFile()
        );
        assertThat(schema.path("$defs").path("action").path("properties").has("aiCallable"))
                .isFalse();
        assertThat(schema.path("$defs").path("query").path("properties").has("aiCallable"))
                .isFalse();

        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve("invalid/module-query-ai-callable-invalid.json").toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("查询 aiCallable 已废弃");
        }
    }

    @Test
    void controlModuleSampleCarriesProtectedSystemMetadata() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            ModuleContract module = catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve("valid/module-system-control.json").toFile()
                    )
            );
            assertThat(module.moduleId()).isEqualTo("module_system_control");
            assertThat(module.system()).isNotNull();
            assertThat(module.system().role()).isEqualTo("MODULE_CONTROL");
            assertThat(module.system().protectedModule()).isTrue();
            assertThat(module.system().capabilities())
                    .containsExactly("MODULE_GOVERNANCE");
            assertThat(module.actions()).extracting(ActionContract::id)
                    .containsExactlyInAnyOrder(
                            "module_system_control.module.set_enabled",
                            "module_system_control.interface.set_callable"
                    );
            assertThat(module.queries()).extracting(QueryContract::id)
                    .containsExactlyInAnyOrder(
                            "module_system_control.module.list",
                            "module_system_control.interface.list",
                            "module_system_control.setting.get"
                    );
        }
    }

    @Test
    void systemServiceSampleCarriesProtectedMetadataWithoutGovernanceCapability()
            throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            ModuleContract module = catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve("valid/module-system-service.json").toFile()
                    )
            );
            assertThat(module.moduleId()).isEqualTo("module_system_example");
            assertThat(module.system().role()).isEqualTo("SYSTEM_SERVICE");
            assertThat(module.system().capabilities()).isEmpty();
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

    @Test
    void reservedSystemPrefixWithoutProtectedMetadataFailsRuntimeValidation()
            throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve(
                                    "invalid/module-system-reserved-prefix-business.json"
                            ).toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module_system_");
        }
    }

    @Test
    void protectedModuleWithoutReservedPrefixFailsRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve(
                                    "invalid/module-system-protected-missing-prefix.json"
                            ).toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module_system_");
        }
    }

    @Test
    void systemServiceCannotClaimControlGovernanceCapability() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve(
                                    "invalid/module-system-service-governance.json"
                            ).toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SYSTEM_SERVICE");
        }
    }

    @Test
    void unsupportedOutputSchemaFailsRuntimeValidation() throws Exception {
        Path missingModules = Path.of("target", "sample-contract-" + UUID.randomUUID())
                .toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missingModules.toString())) {
            assertThatThrownBy(() -> catalog.parseAndValidateModule(
                    objectMapper.readTree(
                            samples.resolve("invalid/module-output-schema-unsupported.json").toFile()
                    )
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outputSchema");
        }
    }
}
