package com.orderly.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.modulecontrol.ModulePreferencesService;
import com.orderly.host.modulecontrol.ModuleRefreshService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.host.support.TestSecondBusinessModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModuleControlIntegrationTest {

    private static final Path FIXTURE = fixtureRoot();
    private static final Path MODULES = FIXTURE.resolve("modules");
    private static final Path SOURCES = FIXTURE.resolve("sources");
    private static final Path DATA = FIXTURE.resolve("data");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", DATA::toString);
        registry.add("orderly.modules-dir", MODULES::toString);
        registry.add("orderly.module-source-dir", SOURCES::toString);
        registry.add("orderly.themes-dir", () -> System.getProperty("orderly.themes-dir"));
    }

    @Autowired
    private ContractCatalog catalog;

    @Autowired
    private ModuleControlService moduleControl;

    @Autowired
    private ModulePreferencesService preferences;

    @Autowired
    private ModuleRefreshService moduleRefresh;

    @Autowired
    private PlanService plans;

    @Autowired
    private ExecutionService execution;

    @Autowired
    private WorkspaceRuntime runtime;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionJournal journal;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void splitsCatalogsAndPersistsActivationOrdersAndSettings() throws Exception {
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].enabled").value(true));
        mockMvc.perform(get("/api/system-modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moduleId").value("module_control"))
                .andExpect(jsonPath("$[0].enabled").value(true));

        mockMvc.perform(put("/api/system/module-preferences/sidebar-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[\"module_business_two\",\"module_business\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/system/module-preferences/sidebar-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleIds":["module_business_two","module_business","module_control"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sidebarOrder[0]").value("module_business_two"));
        mockMvc.perform(put("/api/system/module-preferences/management-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleIds":["module_business_two","module_business"]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/system/module-preferences/management-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleIds":["module_business","module_business_two","module_control"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managementOrder[0]").value("module_business"));

        mockMvc.perform(put("/api/modules/module_business/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showDetails\":false,\"sortBy\":\"modified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showDetails").value(false))
                .andExpect(jsonPath("$.sortBy").value("modified"));
        mockMvc.perform(put("/api/modules/module_business/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortBy\":\"not-valid\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/modules/module_business/settings/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showDetails").value(true))
                .andExpect(jsonPath("$.sortBy").value("name"));

        mockMvc.perform(patch("/api/modules/module_business/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        ModulePreferencesService reloaded = new ModulePreferencesService(
                catalog,
                runtime,
                objectMapper
        );
        reloaded.initialize();
        assertThat(reloaded.isEnabled("module_business")).isFalse();
        assertThat(reloaded.snapshot().sidebarOrder())
                .containsExactly("module_business_two", "module_business", "module_control");
        assertThat(reloaded.snapshot().managementOrder())
                .containsExactly("module_business", "module_business_two", "module_control");

        JsonNode state = objectMapper.readTree(
                runtime.dataRoot().resolve("module-governance.json").toFile()
        );
        assertThat(state.has("enabled")).isTrue();
        assertThat(state.has("sidebarOrder")).isTrue();
        assertThat(state.has("managementOrder")).isTrue();
        assertThat(state.has("moduleSettings")).isTrue();
        assertThat(journal.snapshot()).anyMatch(event ->
                "MODULE_CONTROL".equals(event.type())
                        && "sidebar-order".equals(event.actionId()));
        assertThat(journal.snapshot()).anyMatch(event ->
                "MODULE_CONTROL".equals(event.type())
                        && "settings".equals(event.actionId()));

        moduleControl.setActivation("module_business", true);
        mockMvc.perform(post("/api/system/modules/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").isArray())
                .andExpect(jsonPath("$.issues").isArray())
                .andExpect(jsonPath("$.revision").isString());
        assertThat(journal.snapshot()).anyMatch(event ->
                "MODULE_CONTROL".equals(event.type())
                        && "refresh".equals(event.actionId()));
        mockMvc.perform(post("/api/system/module-folder/open"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTION_HEADER"));
        mockMvc.perform(patch("/api/modules/module_control/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_PROTECTED"));
    }

    @Test
    void disabledModuleRejectsQueriesAndExistingPlans() throws Exception {
        PlanView preview = plans.create("执行业务");
        moduleControl.setActivation("module_business", false);
        try {
            mockMvc.perform(get("/api/modules/module_business/query/business.list"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
            mockMvc.perform(post("/api/plans")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"goal\":\"执行业务\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("QUESTION"));
            assertThatThrownBy(() -> execution.confirm(preview.planId(), true, ""))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("已停用");
        } finally {
            moduleControl.setActivation("module_business", true);
        }
    }

    @Test
    void activationIsRejectedWhileExecutingAndRollbackStillWorksWhenDisabled() throws Exception {
        PlanView plan = plans.create("执行业务");
        TestBusinessModule.blockNextExecution();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionRunResult> running = executor.submit(
                    () -> execution.confirm(plan.planId(), true, "")
            );
            TestBusinessModule.awaitExecutionStarted();
            assertThatThrownBy(() -> moduleControl.setActivation("module_business", false))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("执行期间");
            assertThatThrownBy(moduleRefresh::refresh)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("执行期间");
            TestBusinessModule.releaseExecution();
            assertThat(running.get().outcome()).isEqualTo("COMPLETED");
        } finally {
            TestBusinessModule.releaseExecution();
            executor.shutdownNow();
        }

        moduleControl.setActivation("module_business", false);
        ExecutionRunResult rollback = execution.rollback(plan.planId());
        assertThat(rollback.outcome()).isEqualTo("ROLLED_BACK");
        moduleControl.setActivation("module_business", true);
    }

    private static Path fixtureRoot() {
        Path root = Path.of("target", "module-control-fixture-" + UUID.randomUUID())
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
            TestModuleArchives.writeJar(
                    modules,
                    "module_business_two.jar",
                    TestSecondBusinessModule.class,
                    TestModuleArchives.secondBusinessContract(),
                    TestModuleArchives.secondBusinessUi()
            );
            for (String moduleId : List.of("module_control", "module_business", "module_business_two")) {
                TestModuleArchives.createSourceDirectory(sources, moduleId);
            }
            return root;
        } catch (Exception exception) {
            throw new IllegalStateException("创建模块控制测试夹具失败", exception);
        }
    }
}
