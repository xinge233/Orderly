package com.orderly.host.modulecontrol;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.host.support.TestRefreshModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ModuleRefreshIntegrationTest {

    private static final Path FIXTURE = fixtureRoot();
    private static final Path MODULES = FIXTURE.resolve("modules");
    private static final Path SOURCES = FIXTURE.resolve("sources");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> FIXTURE.resolve("data").toString());
        registry.add("orderly.modules-dir", MODULES::toString);
        registry.add("orderly.module-source-dir", SOURCES::toString);
    }

    @Autowired
    private ModuleRefreshService refresh;

    @Autowired
    private ModulePreferencesService preferences;

    @Autowired
    private ContractCatalog catalog;

    @Test
    void refreshOnlyAppendsBuiltModulesAndKeepsOldSnapshotOnFailures() throws Exception {
        String initialRevision = preferences.revision();
        Path refreshSource = TestModuleArchives.createSourceDirectory(SOURCES, "module_refresh");

        ModuleRefreshResult notBuilt = refresh.refresh();
        assertThat(notBuilt.added()).isEmpty();
        assertThat(notBuilt.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("MODULE_NOT_BUILT");
        assertThat(notBuilt.revision()).isEqualTo(initialRevision);
        assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                .containsExactly("module_business");

        TestModuleArchives.writeJar(
                MODULES,
                "module_refresh.jar",
                TestRefreshModule.class,
                TestModuleArchives.refreshContract(),
                TestModuleArchives.refreshUi()
        );
        ModuleRefreshResult appended = refresh.refresh();
        assertThat(appended.added()).extracting(entry -> entry.moduleId())
                .containsExactly("module_refresh");
        assertThat(appended.added().getFirst().enabled()).isTrue();
        assertThat(appended.issues()).isEmpty();
        assertThat(appended.revision()).isNotEqualTo(initialRevision);
        assertThat(preferences.isEnabled("module_refresh")).isTrue();
        assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                .containsExactly("module_business", "module_refresh");

        TestModuleArchives.createSourceDirectory(SOURCES, "module_other_control");
        TestModuleArchives.writeJar(
                MODULES,
                "module_other_control.jar",
                TestRefreshModule.class,
                TestModuleArchives.secondSystemContract(),
                TestModuleArchives.refreshUi()
        );
        ModuleRefreshResult systemCandidate = refresh.refresh();
        assertThat(systemCandidate.added()).isEmpty();
        assertThat(systemCandidate.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("MODULE_LOAD_FAILED");
        assertThat(catalog.systemModules()).extracting(module -> module.moduleId())
                .containsExactly("module_control");
        assertThat(catalog.businessModules()).hasSize(2);

        Path refreshPom = refreshSource.resolve("pom.xml");
        byte[] originalPom = Files.readAllBytes(refreshPom);
        FileTime originalModified = Files.getLastModifiedTime(refreshPom);
        byte[] changedPom = originalPom.clone();
        changedPom[changedPom.length / 2] = changedPom[changedPom.length / 2] == 'x'
                ? (byte) 'y'
                : (byte) 'x';
        Files.write(refreshPom, changedPom);
        Files.setLastModifiedTime(refreshPom, originalModified);
        ModuleRefreshResult contentChanged = refresh.refresh();
        assertThat(contentChanged.added()).isEmpty();
        assertThat(contentChanged.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("RESTART_REQUIRED");

        Files.writeString(
                refreshSource.resolve("pom.xml"),
                "<project><name>changed</name></project>\n",
                StandardCharsets.UTF_8
        );
        ModuleRefreshResult updated = refresh.refresh();
        assertThat(updated.added()).isEmpty();
        assertThat(updated.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("RESTART_REQUIRED");
        assertThat(catalog.businessModules()).hasSize(2);

        TestModuleArchives.createSourceDirectory(SOURCES, "module_duplicate");
        Files.copy(MODULES.resolve("module_refresh.jar"), MODULES.resolve("module_duplicate.jar"));
        ModuleRefreshResult duplicate = refresh.refresh();
        assertThat(duplicate.added()).isEmpty();
        assertThat(duplicate.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("MODULE_LOAD_FAILED");
        assertThat(catalog.businessModules()).hasSize(2);

        Path moved = FIXTURE.resolve("moved-module-business-source");
        Files.move(SOURCES.resolve("module_business"), moved);
        ModuleRefreshResult deleted = refresh.refresh();
        assertThat(deleted.added()).isEmpty();
        assertThat(deleted.issues())
                .extracting(ModuleRefreshIssue::code)
                .contains("RESTART_REQUIRED");
        assertThat(catalog.businessModules()).extracting(module -> module.moduleId())
                .contains("module_business");
    }

    private static Path fixtureRoot() {
        Path root = Path.of("target", "module-refresh-fixture-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path modules = root.resolve("modules");
        Path sources = root.resolve("sources");
        try {
            Files.createDirectories(root.resolve("data"));
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
            for (String moduleId : List.of("module_control", "module_business")) {
                TestModuleArchives.createSourceDirectory(sources, moduleId);
            }
            return root;
        } catch (Exception exception) {
            throw new IllegalStateException("创建刷新测试夹具失败", exception);
        }
    }
}
