package com.orderly.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.modulecontrol.ModuleLifecycleService;
import com.orderly.host.modulecontrol.ModulePreferencesService;
import com.orderly.host.modulecontrol.ModuleRefreshResult;
import com.orderly.host.modulecontrol.ModuleRefreshService;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.host.support.TestRefreshModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证后台模块切片从待构建、优先请求到显式追加加载的完整路径。
 *
 * <p>启动快照只包含控制模块和既有业务模块；待构建源码只用于展示与排序，不进入
 * 契约目录。写入经过校验的 JAR 后，刷新应原子追加模块并保留治理偏好。</p>
 */
@SpringBootTest
class ModuleLazyLoadIntegrationTest {

    private static final Path FIXTURE = fixtureRoot();
    private static final Path MODULES = FIXTURE.resolve("modules");
    private static final Path SOURCES = FIXTURE.resolve("sources");
    private static final Path BUILD_STATUS = FIXTURE.resolve("target/module-build-status.json");
    private static final Path BUILD_PRIORITY = FIXTURE.resolve("target/module-build-priority.json");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> FIXTURE.resolve("data").toString());
        registry.add("orderly.modules-dir", MODULES::toString);
        registry.add("orderly.module-source-dir", SOURCES::toString);
        registry.add("orderly.module-build-status", BUILD_STATUS::toString);
        registry.add("orderly.module-build-priority", BUILD_PRIORITY::toString);
        registry.add("orderly.background-modules-enabled", () -> "true");
    }

    @Autowired
    private ContractCatalog catalog;

    @Autowired
    private ModuleLifecycleService lifecycle;

    @Autowired
    private ModulePreferencesService preferences;

    @Autowired
    private ModuleRefreshService refresh;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void queuedSourceCanBePrioritizedAndAppendedAfterJarAppears() throws Exception {
        ModuleLifecycleService.Snapshot initial = lifecycle.snapshot();
        assertThat(initial.mode()).isEqualTo("background");
        assertThat(initial.complete()).isFalse();
        assertThat(initial.modules())
                .extracting(ModuleLifecycleService.Entry::sourceName)
                .contains("module_business", "module_refresh");
        assertThat(initial.modules())
                .filteredOn(entry -> entry.sourceName().equals("module_business"))
                .singleElement()
                .extracting(ModuleLifecycleService.Entry::status)
                .isEqualTo("READY");
        assertThat(initial.modules())
                .filteredOn(entry -> entry.sourceName().equals("module_refresh"))
                .singleElement()
                .extracting(ModuleLifecycleService.Entry::status)
                .isEqualTo("QUEUED");
        assertThat(preferences.snapshot().sidebarOrder())
                .doesNotContain("module_refresh");
        assertThat(preferences.isEnabled("module_refresh")).isTrue();
        preferences.updateSidebarOrder(List.of("module_business", "module_system_control"));
        assertThat(preferences.snapshot().sidebarOrder())
                .containsExactly("module_business", "module_system_control");
        assertThatThrownBy(lifecycle::requireReadyForAi)
                .isInstanceOf(com.orderly.host.api.ApiException.class)
                .hasMessageContaining("AI 回合已暂缓");

        ModuleLifecycleService.Snapshot prioritized = lifecycle.prioritize("module_refresh");
        assertThat(prioritized.modules().getFirst().sourceName())
                .isEqualTo("module_refresh");
        JsonNode priority = objectMapper.readTree(BUILD_PRIORITY.toFile());
        assertThat(priority.path("sourceName").asText()).isEqualTo("module_refresh");
        assertThat(priority.size()).isEqualTo(1);

        TestModuleArchives.writeJar(
                MODULES,
                "module_refresh.jar",
                TestRefreshModule.class,
                TestModuleArchives.refreshContract(),
                TestModuleArchives.refreshUi()
        );
        ModuleRefreshResult result = refresh.refresh();

        assertThat(result.added())
                .extracting(entry -> entry.moduleId())
                .containsExactly("module_refresh");
        assertThat(result.issues()).isEmpty();
        assertThat(catalog.businessModules())
                .extracting(module -> module.moduleId())
                .containsExactly("module_business", "module_refresh");
        assertThat(preferences.isEnabled("module_refresh")).isTrue();
        assertThat(preferences.snapshot().sidebarOrder())
                .containsExactly("module_business", "module_refresh", "module_system_control");

        ModuleLifecycleService.Snapshot complete = lifecycle.snapshot();
        assertThat(complete.modules())
                .filteredOn(entry -> entry.sourceName().equals("module_refresh"))
                .singleElement()
                .extracting(ModuleLifecycleService.Entry::status)
                .isEqualTo("READY");
        assertThatCode(lifecycle::requireReadyForAi).doesNotThrowAnyException();
    }

    private static Path fixtureRoot() {
        Path root = Path.of("target", "module-lazy-load-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path modules = root.resolve("modules");
        Path sources = root.resolve("sources");
        try {
            Files.createDirectories(root.resolve("data"));
            Files.createDirectories(root.resolve("target"));
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
            for (String moduleId : List.of(
                    "module_system_control",
                    "module_business",
                    "module_refresh"
            )) {
                TestModuleArchives.createSourceDirectory(sources, moduleId);
            }
            Files.writeString(
                    root.resolve("target/module-build-status.json"),
                    """
                    {
                      "schemaVersion": 1,
                      "mode": "background",
                      "updatedAt": "2026-11-20T00:00:00Z",
                      "complete": false,
                      "modules": [
                        {
                          "sourceName": "module_refresh",
                          "status": "READY",
                          "message": "上次运行遗留的已构建状态"
                        }
                      ]
                    }
                    """
            );
            return root;
        } catch (Exception exception) {
            throw new IllegalStateException("创建懒加载测试夹具失败", exception);
        }
    }
}
