package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.host.support.TestRefreshModule;
import com.orderly.kernel.ModuleRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModuleSystemValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingControlModuleFailsStartupValidation() throws Exception {
        Path directory = testDirectory("missing-control");
        TestModuleArchives.writeJar(
                directory,
                "module_business.jar",
                TestBusinessModule.class,
                TestModuleArchives.businessContract(),
                TestModuleArchives.businessUi()
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, directory.toString())) {
            assertThatThrownBy(catalog::validateSystemModules)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MODULE_CONTROL");
        }
    }

    @Test
    void duplicateSystemRolesFailStartupValidation() throws Exception {
        Path directory = testDirectory("duplicate-control");
        TestModuleArchives.writeJar(
                directory,
                "module_control.jar",
                TestControlModule.class,
                TestModuleArchives.controlContract(),
                TestModuleArchives.controlUi()
        );
        ObjectNode business = (ObjectNode) objectMapper.readTree(TestModuleArchives.businessContract());
        ObjectNode system = business.putObject("system");
        system.put("role", "MODULE_CONTROL");
        system.put("protected", true);
        TestModuleArchives.writeJar(
                directory,
                "module_business.jar",
                TestBusinessModule.class,
                objectMapper.writeValueAsString(business),
                TestModuleArchives.businessUi()
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, directory.toString())) {
            assertThatThrownBy(catalog::validateSystemModules)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("只能加载一个");
        }
    }

    @Test
    void exactControlModuleValidatesAndBusinessModuleStaysSeparate() throws Exception {
        Path directory = testDirectory("valid-system");
        TestModuleArchives.writeJar(
                directory,
                "module_control.jar",
                TestControlModule.class,
                TestModuleArchives.controlContract(),
                TestModuleArchives.controlUi()
        );
        TestModuleArchives.writeJar(
                directory,
                "module_business.jar",
                TestBusinessModule.class,
                TestModuleArchives.businessContract(),
                TestModuleArchives.businessUi()
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, directory.toString())) {
            catalog.validateSystemModules();
            assertThat(catalog.systemModules()).extracting(module -> module.moduleId())
                    .containsExactly("module_control");
            assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                    .containsExactly("module_business");
        }
    }

    @Test
    void preparedModuleDoesNotChangeSnapshotUntilCommitAndCanRollback() throws Exception {
        Path directory = testDirectory("prepared-transaction");
        TestModuleArchives.writeJar(
                directory,
                "module_control.jar",
                TestControlModule.class,
                TestModuleArchives.controlContract(),
                TestModuleArchives.controlUi()
        );
        TestModuleArchives.writeJar(
                directory,
                "module_business.jar",
                TestBusinessModule.class,
                TestModuleArchives.businessContract(),
                TestModuleArchives.businessUi()
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, directory.toString())) {
            TestModuleArchives.writeJar(
                    directory,
                    "module_refresh.jar",
                    TestRefreshModule.class,
                    TestModuleArchives.refreshContract(),
                    TestModuleArchives.refreshUi()
            );
            try (ContractCatalog.PreparedModule prepared = catalog.prepareModule(
                    directory.resolve("module_refresh.jar"),
                    mock(ModuleRuntime.class),
                    objectMapper
            )) {
                assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                        .containsExactly("module_business");
                prepared.commit();
                assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                        .containsExactly("module_business", "module_refresh");
                prepared.rollback();
                assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                        .containsExactly("module_business");
            }
        }
    }

    private Path testDirectory(String prefix) {
        return Path.of("target", "module-system-" + prefix + "-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
    }
}
