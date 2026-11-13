package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "module_control.jar",
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
                    objectMapper
            );
            preferences.initialize();

            Path state = data.resolve("module-governance.json");
            Files.writeString(state, "{broken-json", StandardCharsets.UTF_8);
            ModulePreferencesService corrupted = new ModulePreferencesService(
                    catalog,
                    runtime,
                    objectMapper
            );
            assertThatThrownBy(corrupted::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模块治理状态文件损坏")
                    .hasMessageContaining("module-governance.json");
        }
    }
}
