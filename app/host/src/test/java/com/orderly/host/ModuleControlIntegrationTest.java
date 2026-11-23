package com.orderly.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.ai.AiContracts;
import com.orderly.host.ai.AiPlanService;
import com.orderly.host.ai.AiServiceClient;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.execution.ExecutionRecordStep;
import com.orderly.host.execution.ExecutionRecordStore;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.modulecontrol.ModulePreferencesService;
import com.orderly.host.modulecontrol.ModuleRefreshService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.system.SystemFeatureService;
import com.orderly.kernel.RiskLevel;
import com.orderly.host.support.TestBusinessModule;
import com.orderly.host.support.TestControlModule;
import com.orderly.host.support.TestModuleArchives;
import com.orderly.host.support.TestSecondBusinessModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private ExecutionRecordStore executionRecords;

    @Autowired
    private AiActionService aiActions;

    @Autowired
    private AiPlanService aiPlans;

    @Autowired
    private WorkspaceRuntime runtime;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionJournal journal;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SystemFeatureService features;

    @MockBean
    private AiServiceClient aiService;

    @BeforeEach
    void enablePlanning() {
        features.setPlanningEnabled(true);
        org.mockito.Mockito.when(aiService.settings()).thenReturn(new AiContracts.SettingsView(
                AiContracts.Mode.DEEPSEEK,
                "https://api.deepseek.com",
                "deepseek-flash",
                true,
                false,
                false,
                0
        ));
        org.mockito.Mockito.when(aiService.settings(any()))
                .thenReturn(new AiContracts.SettingsView(
                        AiContracts.Mode.DEEPSEEK,
                        "https://api.deepseek.com",
                        "deepseek-flash",
                        true,
                        false,
                        false,
                        0
                ));
        org.mockito.Mockito.when(aiService.beginTurn()).thenReturn(
                new AiServiceClient.TurnBudget(Instant.now().plusSeconds(60))
        );
        org.mockito.Mockito.when(aiService.continueWithQueryResults(any(), any()))
                .thenReturn(new AiContracts.ServiceDecision(
                        "QUESTION",
                        "module-system-control-query-conversation",
                        "查询结果已自动用于后续决策",
                        null,
                        null,
                        new AiContracts.Question("继续处理？", List.of("继续", "取消"))
                ));
    }

    @AfterEach
    void releaseBlockedBusinessModule() {
        TestBusinessModule.releaseExecution();
    }

    @Test
    void splitsCatalogsAndPersistsActivationOrdersAndSettings() throws Exception {
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].enabled").value(true));
        mockMvc.perform(get("/api/system-modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moduleId").value("module_system_control"))
                .andExpect(jsonPath("$[0].enabled").value(true));

        mockMvc.perform(put("/api/system/module-preferences/sidebar-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[\"module_business_two\",\"module_business\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/system/module-preferences/sidebar-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleIds":["module_business_two","module_business","module_system_control"]}
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
                                {"moduleIds":["module_business","module_business_two","module_system_control"]}
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
                objectMapper,
                SOURCES.toString()
        );
        reloaded.initialize();
        assertThat(reloaded.isEnabled("module_business")).isFalse();
        assertThat(reloaded.snapshot().sidebarOrder())
                .containsExactly("module_business_two", "module_business", "module_system_control");
        assertThat(reloaded.snapshot().managementOrder())
                .containsExactly("module_business", "module_business_two", "module_system_control");

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
        mockMvc.perform(patch("/api/modules/module_system_control/activation")
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
    void legacyPlanDoesNotUseDynamicActionAiGovernance() throws Exception {
        moduleControl.setInterfaceAiCallable("module_business", "action", "business.write", false);
        try {
            PlanView plan = plans.create("执行业务");
            assertThat(plan.kind()).isEqualTo("PLAN");
            assertThat(plan.steps()).singleElement().satisfies(step ->
                    assertThat(step.actionId()).isEqualTo("business.write"));

            ExecutionRunResult result = execution.confirm(plan.planId(), true, "");
            assertThat(result.outcome()).isEqualTo("COMPLETED");
        } finally {
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    true
            );
        }
    }

    @Test
    void aiQueriesRespectEnabledAndDynamicAiCallableGates() throws Exception {
        org.mockito.Mockito.when(aiService.createTurn(any(), any()))
                .thenReturn(queryDecision("business.list"));

        mockMvc.perform(post("/api/ai/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "读取业务",
                                  "conversationId": null,
                                  "clientRequestId": "query-ok"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.queryResults[0].status").value("OK"))
                .andExpect(jsonPath("$.queryResults[0].payload.count").value(1));

        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/query/business.list/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":false}"))
                .andExpect(status().isOk());
        try {
            org.mockito.Mockito.when(aiService.createTurn(any(), any()))
                    .thenReturn(queryDecision("business.list"));
            mockMvc.perform(post("/api/ai/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "goal": "读取动态禁用查询",
                                      "conversationId": null,
                                      "clientRequestId": "query-dynamic-disabled"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.queryResults[0].status").value("FAILED"));
        } finally {
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "query",
                    "business.list",
                    true
            );
        }

        moduleControl.setActivation("module_business", false);
        try {
            org.mockito.Mockito.when(aiService.createTurn(any(), any()))
                    .thenReturn(queryDecision("business.list"));
            mockMvc.perform(post("/api/ai/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "goal": "读取停用模块",
                                      "conversationId": null,
                                      "clientRequestId": "query-module-disabled"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.queryResults[0].status").value("FAILED"));
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
            ExecutionRunResult result = running.get(5, TimeUnit.SECONDS);
            assertThat(result.outcome())
                    .withFailMessage("宿主返回 %s：%s", result.outcome(), result.message())
                    .isEqualTo("COMPLETED");
        } finally {
            TestBusinessModule.releaseExecution();
            executor.shutdownNow();
        }

        moduleControl.setActivation("module_business", false);
        ExecutionRunResult rollback = execution.rollback(plan.planId());
        assertThat(rollback.outcome()).isEqualTo("ROLLED_BACK");
        moduleControl.setActivation("module_business", true);
    }

    @Test
    void interfaceAiCallableIsHostOwnedAndPlanUsageLocksActions() throws Exception {
        mockMvc.perform(get("/api/system/module-interfaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionAiCallableEnabled.module_business['business.write']")
                        .value(true))
                .andExpect(jsonPath("$.queryAiCallableEnabled.module_business['business.list']")
                        .value(true));
        mockMvc.perform(get("/api/system/module-interfaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionAiCallableEnabled.module_system_control").doesNotExist())
                .andExpect(jsonPath("$.queryAiCallableEnabled.module_system_control").doesNotExist());

        AiContracts.Plan pending = aiPlans.create(
                "占用接口",
                List.of(new AiContracts.Action(
                        "module_business",
                        "business.write",
                        Map.of("label", "占用")
                ))
        );
        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/action/business.write/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERFACE_IN_USE"));

        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/query/business.list/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryAiCallableEnabled.module_business['business.list']")
                        .value(false))
                .andExpect(jsonPath("$.inUseActions[0]")
                        .value("module_business/business.write"));
        mockMvc.perform(get("/api/modules/module_business/query/business.list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(patch(
                        "/api/modules/module_system_control/interfaces/query/control.list/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_PROTECTED"));

        aiPlans.confirm(pending.planId());
        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/action/business.write/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionAiCallableEnabled.module_business['business.write']")
                        .value(false));

        assertThatThrownBy(() -> aiActions.preview(List.of(
                new AiActionService.ActionCall(
                        "module_business",
                        "business.write",
                        Map.of("label", "禁用")
                )
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("用户关闭 AI 调用");

        ModulePreferencesService reloaded = new ModulePreferencesService(
                catalog,
                runtime,
                objectMapper,
                SOURCES.toString()
        );
        reloaded.initialize();
        assertThat(reloaded.isActionAiCallableEnabled(
                "module_business",
                "business.write"
        )).isFalse();
        assertThat(reloaded.isQueryAiCallableEnabled(
                "module_business",
                "business.list"
        )).isFalse();

        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/action/business.write/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(
                        "/api/modules/module_business/interfaces/query/business.list/ai-callable"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiCallable\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void systemControlQueriesExposeBusinessGovernanceState() throws Exception {
        mockMvc.perform(get(
                        "/api/modules/module_system_control/query/module_system_control.module.list"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").isNumber())
                .andExpect(jsonPath("$.items[0].moduleId").value("module_business"));

        mockMvc.perform(get(
                        "/api/modules/module_system_control/query/module_system_control.interface.list"
                )
                        .param("moduleId", "module_business")
                        .param("kind", "action"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].interfaceId").value("business.write"))
                .andExpect(jsonPath("$.items[0].aiCallable").value(true));

        mockMvc.perform(get(
                        "/api/modules/module_system_control/query/module_system_control.setting.get"
                )
                        .param("moduleId", "module_business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleId").value("module_business"))
                .andExpect(jsonPath("$.fields").isArray())
                .andExpect(jsonPath("$.values.showDetails").value(true));
    }

    @Test
    void systemControlActionsModifyAndRollbackModuleGovernanceState() {
        boolean originallyEnabled = preferences.isEnabled("module_business");
        boolean originallyCallable = preferences.isActionAiCallableEnabled(
                "module_business",
                "business.write"
        );
        try {
            ExecutionRecord activation = aiActions.execute(true, List.of(
                    new AiActionService.ActionCall(
                            "module_system_control",
                            "module_system_control.module.set_enabled",
                            Map.of("moduleId", "module_business", "enabled", false)
                    )
            ));
            assertThat(activation.status())
                    .withFailMessage(
                            "系统治理 Action 返回 %s：%s",
                            activation.status(),
                            activation.steps()
                    )
                    .isEqualTo("COMPLETED");
            assertThat(preferences.isEnabled("module_business")).isFalse();

            ExecutionRecord activationRollback = execution.rollbackExecution(
                    activation.executionId()
            );
            assertThat(activationRollback.status()).isEqualTo("ROLLED_BACK");
            assertThat(preferences.isEnabled("module_business")).isEqualTo(originallyEnabled);

            ExecutionRecord callable = aiActions.execute(true, List.of(
                    new AiActionService.ActionCall(
                            "module_system_control",
                            "module_system_control.interface.set_callable",
                            Map.of(
                                    "moduleId", "module_business",
                                    "kind", "action",
                                    "interfaceId", "business.write",
                                    "enabled", false
                            )
                    )
            ));
            assertThat(callable.status())
                    .withFailMessage(
                            "系统治理 Action 返回 %s：%s",
                            callable.status(),
                            callable.steps()
                    )
                    .isEqualTo("COMPLETED");
            assertThat(preferences.isActionAiCallableEnabled(
                    "module_business",
                    "business.write"
            )).isFalse();

            ExecutionRecord callableRollback = execution.rollbackExecution(
                    callable.executionId()
            );
            assertThat(callableRollback.status()).isEqualTo("ROLLED_BACK");
            assertThat(preferences.isActionAiCallableEnabled(
                    "module_business",
                    "business.write"
            )).isEqualTo(originallyCallable);
        } finally {
            moduleControl.setActivation("module_business", originallyEnabled);
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    originallyCallable
            );
        }
    }

    @Test
    void legacyControlExecutionRecordCanStillRollbackByAlias() {
        boolean originallyEnabled = preferences.isEnabled("module_business");
        try {
            moduleControl.setActivation("module_business", false);
            Instant now = Instant.now();
            ExecutionRecordStep step = new ExecutionRecordStep(
                    "a1",
                    "module_control",
                    "module_control.module.set_enabled",
                    "设置模块启停",
                    RiskLevel.WRITE,
                    Map.of("moduleId", "module_business", "enabled", false),
                    "COMPLETED",
                    Map.of(
                            "moduleId", "module_business",
                            "enabled", originallyEnabled,
                            "revision", "legacy-snapshot"
                    ),
                    Map.of(
                            "moduleId", "module_business",
                            "previousEnabled", originallyEnabled,
                            "enabled", false,
                            "changed", true,
                            "revision", "legacy-output"
                    ),
                    "旧版模块启停记录"
            );
            String executionId = UUID.randomUUID().toString();
            ExecutionRecord legacy = new ExecutionRecord(
                    executionId,
                    "AI_DIRECT",
                    "COMPLETED",
                    List.of(step),
                    ExecutionRecord.aggregate(
                            List.of(step),
                            ExecutionRecord.StepPart.ARGUMENTS
                    ),
                    ExecutionRecord.aggregate(
                            List.of(step),
                            ExecutionRecord.StepPart.OUTPUT
                    ),
                    ExecutionRecord.aggregate(
                            List.of(step),
                            ExecutionRecord.StepPart.SNAPSHOT
                    ),
                    List.of(),
                    now,
                    now,
                    now,
                    now
            );
            executionRecords.save(legacy);

            ExecutionRecord rolledBack = execution.rollbackExecution(executionId);

            assertThat(rolledBack.status()).isEqualTo("ROLLED_BACK");
            assertThat(preferences.isEnabled("module_business")).isEqualTo(originallyEnabled);
            assertThat(rolledBack.steps().getFirst().moduleId()).isEqualTo("module_control");
            assertThat(rolledBack.steps().getFirst().actionId())
                    .isEqualTo("module_control.module.set_enabled");
        } finally {
            moduleControl.setActivation("module_business", originallyEnabled);
        }
    }

    @Test
    void systemControlActionsCannotBeMixedWithBusinessActions() {
        assertThatThrownBy(() -> aiActions.preview(List.of(
                new AiActionService.ActionCall(
                        "module_system_control",
                        "module_system_control.module.set_enabled",
                        Map.of("moduleId", "module_business", "enabled", false)
                ),
                new AiActionService.ActionCall(
                        "module_business",
                        "business.write",
                        Map.of("label", "混批")
                )
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("SYSTEM_ACTION_MUST_RUN_ALONE"));
    }

    @Test
    void interfaceUsageStayLockedForTheWholePlanExecutionAndReleaseAfterward() throws Exception {
        TestBusinessModule.blockNextExecution();
        AiContracts.Plan plan = aiPlans.create(
                "执行期间占用接口",
                List.of(new AiContracts.Action(
                        "module_business",
                        "business.write",
                        Map.of("label", "执行期间占用")
                ))
        );
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionRecord> running = executor.submit(
                    () -> aiPlans.confirm(plan.planId())
            );
            TestBusinessModule.awaitExecutionStarted();

            assertThatThrownBy(() -> moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    false
            ))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("正在被");
            assertThat(moduleControl.interfaceState().inUseActions())
                    .contains("module_business/business.write");

            TestBusinessModule.releaseExecution();
            ExecutionRecord completed = running.get(5, TimeUnit.SECONDS);
            assertThat(completed.status()).isIn("COMPLETED", "FAILED_ROLLED_BACK");
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    false
            );
            assertThat(moduleControl.interfaceState().inUseActions()).isEmpty();
        } finally {
            TestBusinessModule.releaseExecution();
            executor.shutdownNow();
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    true
            );
        }
    }

    @Test
    void interfaceStateReadsAndActionTogglesDoNotDeadlock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> reads = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 100; index++) {
                    moduleControl.interfaceState();
                }
                return null;
            });
            Future<?> writes = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 10; index++) {
                    moduleControl.setInterfaceAiCallable(
                            "module_business",
                            "action",
                            "business.write",
                            index % 2 == 0
                    );
                }
                return null;
            });
            start.countDown();
            reads.get(5, TimeUnit.SECONDS);
            writes.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            moduleControl.setInterfaceAiCallable(
                    "module_business",
                    "action",
                    "business.write",
                    true
            );
        }
    }

    private static Path fixtureRoot() {
        Path root = Path.of("target", "module-system-control-fixture-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path modules = root.resolve("modules");
        Path sources = root.resolve("sources");
        try {
            Files.createDirectories(root.resolve("data"));
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
            TestModuleArchives.writeJar(
                    modules,
                    "module_business_two.jar",
                    TestSecondBusinessModule.class,
                    TestModuleArchives.secondBusinessContract(),
                    TestModuleArchives.secondBusinessUi()
            );
            for (String moduleId : List.of("module_system_control", "module_business", "module_business_two")) {
                TestModuleArchives.createSourceDirectory(sources, moduleId);
            }
            return root;
        } catch (Exception exception) {
            throw new IllegalStateException("创建模块控制测试夹具失败", exception);
        }
    }

    private AiContracts.ServiceDecision queryDecision(String queryId) {
        return new AiContracts.ServiceDecision(
                "QUERY",
                "module-system-control-query-conversation",
                "读取本地数据",
                null,
                List.of(new AiContracts.QueryCall(
                        "module_business",
                        queryId,
                        Map.of()
                )),
                null
        );
    }
}
