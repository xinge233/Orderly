package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ModulePreferencesCorruptionTest {

    @Test
    void corruptGovernanceFileFailsWithExplicitPathAndReason() throws Exception {
        Path root = Path.of("target", "module-state-corruption-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path modules = root.resolve("modules");
        Path data = root.resolve("data");
        ObjectMapper objectMapper = new ObjectMapper();
        TestModuleArchives.writeJar(
                modules,
                "module_system_control.jar",
                TestControlModule.class,
                TestModuleArchives.controlContract(),
                TestModuleArchives.controlUi()
        );
        TestModuleArchives.writeJar(
                modules,
                "module_business.jar",
                TestBusinessModule.class,
                TestModuleArchives.businessContract(),
                TestModuleArchives.businessUi()
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, modules.toString())) {
            WorkspaceRuntime runtime = new WorkspaceRuntime(data.toString(), objectMapper);
            runtime.initialize();
            ModulePreferencesService preferences = new ModulePreferencesService(
                    catalog,
                    runtime,
                    objectMapper,
                    root.resolve("sources").toString()
            );
            preferences.initialize();

            Path state = data.resolve("module-governance.json");
            Files.writeString(state, "{broken-json", StandardCharsets.UTF_8);
            ModulePreferencesService corrupted = new ModulePreferencesService(
                    catalog,
                    runtime,
                    objectMapper,
                    root.resolve("sources").toString()
            );
            assertThatThrownBy(corrupted::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模块治理状态文件损坏")
                    .hasMessageContaining("module-governance.json");
        }
    }

    @Test
    void legacyControlIdentityMigratesWithoutLosingOrder() throws Exception {
        Path root = Path.of("target", "module-state-migration-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path modules = root.resolve("modules");
        Path data = root.resolve("data");
        ObjectMapper objectMapper = new ObjectMapper();
        TestModuleArchives.writeJar(
                modules,
                "module_system_control.jar",
                TestControlModule.class,
                TestModuleArchives.controlContract(),
                TestModuleArchives.controlUi()
        );
        TestModuleArchives.writeJar(
                modules,
                "module_business.jar",
                TestBusinessModule.class,
                TestModuleArchives.businessContract(),
                TestModuleArchives.businessUi()
        );
        Files.createDirectories(data);
        Files.writeString(
                data.resolve("module-governance.json"),
                """
                        {
                          "version": 1,
                          "revision": "legacy-revision",
                          "enabled": {"module_business": true},
                          "sidebarOrder": ["module_business", "module_control"],
                          "managementOrder": ["module_control", "module_business"],
                          "moduleSettings": {},
                          "actionAiCallableEnabled": {},
                          "queryAiCallableEnabled": {}
                        }
                        """,
                StandardCharsets.UTF_8
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, modules.toString())) {
            catalog.validateSystemModules();
            WorkspaceRuntime runtime = new WorkspaceRuntime(data.toString(), objectMapper);
            runtime.initialize();
            ModulePreferencesService preferences = new ModulePreferencesService(
                    catalog,
                    runtime,
                    objectMapper,
                    root.resolve("sources").toString()
            );

            preferences.initialize();

            assertThat(preferences.snapshot().sidebarOrder())
                    .containsExactly("module_business", "module_system_control");
            assertThat(preferences.snapshot().managementOrder())
                    .containsExactly("module_system_control", "module_business");
            assertThat(preferences.actionAiRiskLevel("module_business"))
                    .isEqualTo(RiskLevel.DELETE);
            String persisted = Files.readString(
                    data.resolve("module-governance.json"),
                    StandardCharsets.UTF_8
            );
            assertThat(persisted).contains("module_system_control");
            assertThat(persisted).contains("\"actionAiRiskLevels\"");
            assertThat(persisted).doesNotContain("\"module_control\"");
        }
    }
}
