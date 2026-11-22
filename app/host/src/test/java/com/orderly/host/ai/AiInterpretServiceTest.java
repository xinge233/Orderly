package com.orderly.host.ai;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSystemMetadata;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.system.SystemFeatureService;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiInterpretServiceTest {

    @Mock
    private AiServiceClient aiService;
    @Mock
    private ContractCatalog catalog;
    @Mock
    private ModuleControlService moduleControl;
    @Mock
    private AiActionService actions;
    @Mock
    private AiPlanService plans;
    @Mock
    private SystemFeatureService systemFeatures;
    @Mock
    private AiQueryService queries;

    private AiInterpretService service;

    @BeforeEach
    void setUp() {
        service = new AiInterpretService(
                aiService,
                catalog,
                moduleControl,
                actions,
                plans,
                systemFeatures,
                queries
        );
        lenient().when(catalog.businessModules()).thenReturn(List.of());
        lenient().when(catalog.systemModules()).thenReturn(List.of());
        lenient().when(systemFeatures.aiPlansEnabled()).thenReturn(true);
        lenient().when(aiService.beginTurn()).thenReturn(
                new AiServiceClient.TurnBudget(Instant.now().plusSeconds(60))
        );
        lenient().when(aiService.settings(any())).thenReturn(settings(false, false));
    }

    @Test
    void returnsQuestionWithoutExecution() {
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "QUESTION",
                new AiContracts.Question("哪个目录？", List.of("下载目录", "桌面"))
        ));

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "整理一下",
                null,
                "question-request"
        ));

        assertThat(response.kind()).isEqualTo("QUESTION");
        assertThat(response.question().options()).containsExactly("下载目录", "桌面");
        assertThat(response.execution()).isNull();
        verifyNoInteractions(actions, plans, queries);
    }

    @Test
    void defaultsDeleteActionsToIndependentPlan() {
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "ACTIONS",
                new AiContracts.Action(
                        "module_fs",
                        "fs.trash",
                        Map.of("path", "旧文件.txt")
                )
        ));
        when(actions.preview(anyList())).thenReturn(preview(RiskLevel.DELETE, "fs.trash"));
        when(plans.create(any(), anyList())).thenReturn(new AiContracts.Plan(
                "plan-1",
                "删除旧文件",
                "PREVIEW",
                RiskLevel.DELETE,
                List.of(),
                Instant.now(),
                Instant.now().plusSeconds(60)
        ));

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "删除旧文件.txt",
                null,
                "delete-plan-request"
        ));

        assertThat(response.kind()).isEqualTo("PLAN");
        assertThat(response.plan().planId()).isEqualTo("plan-1");
        assertThat(response.execution()).isNull();
    }

    @Test
    void executesWriteActionsWhenIntentIsExplicit() {
        when(systemFeatures.aiPlansEnabled()).thenReturn(false);
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "ACTIONS",
                new AiContracts.Action("module_fs", "fs.mkdir", Map.of("path", "归档"))
        ));
        when(actions.preview(anyList())).thenReturn(preview(RiskLevel.WRITE, "fs.mkdir"));
        ExecutionRecord record = mock(ExecutionRecord.class);
        when(actions.executeAiDirect(anyList())).thenReturn(record);

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "创建归档目录",
                null,
                "write-request"
        ));

        assertThat(response.kind()).isEqualTo("ACTIONS");
        assertThat(response.highestRisk()).isEqualTo(RiskLevel.WRITE);
        assertThat(response.execution()).isSameAs(record);
    }

    @Test
    void queryResultsStayLocalByDefaultAndNeverContinueToTheModel() {
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        List<AiContracts.QueryResult> results = List.of(okQueryResult());
        when(queries.executeAiQueries(anyList())).thenReturn(results);

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-private-request"
        ));

        assertThat(response.kind()).isEqualTo("QUERY");
        assertThat(response.queryResults()).isEqualTo(results);
        verify(queries).executeAiQueries(anyList());
        verify(aiService, never()).continueWithQueryResults(any(), any());
        verifyNoInteractions(actions, plans);
    }

    @Test
    void explicitQueryResultSharingSendsBoundedResultsAndOnlyUsesFinalDecisionKinds() {
        when(aiService.settings(any())).thenReturn(settings(false, true));
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        List<AiContracts.QueryResult> results = List.of(okQueryResult());
        when(queries.executeAiQueries(anyList())).thenReturn(results);
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "QUESTION",
                        "conversation-query",
                        "message",
                        null,
                        null,
                        new AiContracts.Question("还需要哪一天？", List.of("今天", "明天"))
                )
        );

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-shared-request"
        ));

        ArgumentCaptor<AiContracts.ServiceQueryContinuationRequest> captor =
                ArgumentCaptor.forClass(
                        AiContracts.ServiceQueryContinuationRequest.class
                );
        verify(aiService).continueWithQueryResults(captor.capture(), any());
        assertThat(captor.getValue().goal()).isEqualTo("看看待办");
        assertThat(captor.getValue().modules()).isEmpty();
        assertThat(captor.getValue().queryResults()).isEqualTo(results);
        assertThat(response.kind()).isEqualTo("QUESTION");
        assertThat(response.queryResults()).isEqualTo(results);
        verifyNoInteractions(actions, plans);
    }

    @Test
    void validatesAndReturnsViewWithoutChangingQueryPayload() {
        when(aiService.settings(any())).thenReturn(settings(false, true));
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        List<AiContracts.QueryResult> results = List.of(okQueryResult());
        when(queries.executeAiQueries(anyList())).thenReturn(results);
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "VIEW",
                        "conversation-query",
                        "已整理结果",
                        null,
                        null,
                        null,
                        new AiContracts.QueryView(
                                1,
                                "table",
                                "待办列表",
                                "items",
                                List.of(
                                        new AiContracts.ViewField("title", "标题", "text"),
                                        new AiContracts.ViewField("status", "状态", "status")
                                ),
                                "暂无待办"
                        )
                )
        );

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-view-request"
        ));

        assertThat(response.kind()).isEqualTo("VIEW");
        assertThat(response.view().title()).isEqualTo("待办列表");
        assertThat(response.queryResults()).isEqualTo(results);
        assertThat(results.getFirst().payload()).isEqualTo(
                Map.of("items", List.of(Map.of("title", "项目周报", "status", "OPEN")))
        );
        verifyNoInteractions(actions, plans);
    }

    @Test
    void invalidViewFallsBackToLocalQueryResults() {
        when(aiService.settings(any())).thenReturn(settings(false, true));
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        List<AiContracts.QueryResult> results = List.of(okQueryResult());
        when(queries.executeAiQueries(anyList())).thenReturn(results);
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "VIEW",
                        "conversation-query",
                        "非法展示",
                        null,
                        null,
                        null,
                        new AiContracts.QueryView(
                                1,
                                "table",
                                "待办列表",
                                "items",
                                List.of(new AiContracts.ViewField(
                                        "missing",
                                        "不存在",
                                        "text"
                                )),
                                "暂无待办"
                        )
                )
        );

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-view-fallback-request"
        ));

        assertThat(response.kind()).isEqualTo("QUERY");
        assertThat(response.message()).contains("已显示本地查询结果");
        assertThat(response.queryResults()).isEqualTo(results);
        verifyNoInteractions(actions, plans);
    }

    @Test
    void normalizesKeyValueViewAndAllowsNullFields() {
        when(aiService.settings(any())).thenReturn(settings(false, true));
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "项目周报");
        row.put("note", null);
        List<AiContracts.QueryResult> results = List.of(new AiContracts.QueryResult(
                "module_todo",
                "todo.list",
                AiContracts.QueryStatus.OK,
                Map.of("items", List.of(row)),
                "查询完成"
        ));
        when(queries.executeAiQueries(anyList())).thenReturn(results);
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "VIEW",
                        "conversation-query",
                        "已整理结果",
                        null,
                        null,
                        null,
                        new AiContracts.QueryView(
                                1,
                                "keyValue",
                                "待办摘要",
                                "items",
                                List.of(new AiContracts.ViewField("note", "备注", "text")),
                                "暂无待办"
                        )
                )
        );

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-view-null-field"
        ));

        assertThat(response.kind()).isEqualTo("VIEW");
        assertThat(response.view().layout()).isEqualTo("keyValue");
        assertThat(response.queryResults()).isEqualTo(results);
        verifyNoInteractions(actions, plans);
    }

    @Test
    void tableViewRequiresAnArrayRowPath() {
        when(aiService.settings(any())).thenReturn(settings(false, true));
        when(aiService.createTurn(any(), any())).thenReturn(queryDecision());
        List<AiContracts.QueryResult> results = List.of(new AiContracts.QueryResult(
                "module_todo",
                "todo.list",
                AiContracts.QueryStatus.OK,
                Map.of("title", "项目周报"),
                "查询完成"
        ));
        when(queries.executeAiQueries(anyList())).thenReturn(results);
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "VIEW",
                        "conversation-query",
                        "非法展示",
                        null,
                        null,
                        null,
                        new AiContracts.QueryView(
                                1,
                                "table",
                                "待办列表",
                                "title",
                                List.of(),
                                "暂无待办"
                        )
                )
        );

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "看看待办",
                null,
                "query-view-row-path"
        ));

        assertThat(response.kind()).isEqualTo("QUERY");
        assertThat(response.message()).contains("已显示本地查询结果");
        assertThat(response.queryResults()).isEqualTo(results);
        verifyNoInteractions(actions, plans);
    }

    @Test
    void requiresClientRequestIdAndReplaysTheFirstResult() {
        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "创建归档目录",
                null,
                null
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("AI_CLIENT_REQUEST_ID_REQUIRED");
                });
        verifyNoInteractions(aiService, actions, plans, queries);

        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "QUESTION",
                new AiContracts.Question("哪个目录？", List.of())
        ));
        AiContracts.TurnRequest replayRequest = new AiContracts.TurnRequest(
                "整理目录",
                null,
                "client-request-1"
        );
        AiContracts.TurnResponse first = service.interpret(replayRequest);
        AiContracts.TurnResponse replay = service.interpret(replayRequest);

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        verify(aiService, times(1)).createTurn(any(), any());
    }

    @Test
    void replayKeyIncludesSettingsVersion() {
        when(aiService.settings(any())).thenReturn(
                settings(false, false, 0),
                settings(false, false, 1)
        );
        when(aiService.createTurn(any(), any())).thenReturn(
                decision("QUESTION", new AiContracts.Question("第一次？", List.of())),
                decision("QUESTION", new AiContracts.Question("第二次？", List.of()))
        );
        AiContracts.TurnRequest request = new AiContracts.TurnRequest(
                "整理目录",
                null,
                "settings-version-request"
        );

        AiContracts.TurnResponse first = service.interpret(request);
        AiContracts.TurnResponse second = service.interpret(request);

        assertThat(first.question().prompt()).isEqualTo("第一次？");
        assertThat(second.question().prompt()).isEqualTo("第二次？");
        verify(aiService, times(2)).createTurn(any(), any());
    }

    @Test
    void reusingClientRequestIdForDifferentGoalsIsRejected() {
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "QUESTION",
                new AiContracts.Question("哪个目录？", List.of())
        ));
        service.interpret(new AiContracts.TurnRequest(
                "整理目录",
                null,
                "client-request-conflict"
        ));

        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "删除目录",
                null,
                "client-request-conflict"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("AI_CLIENT_REQUEST_ID_CONFLICT");
                });
        verify(aiService, times(1)).createTurn(any(), any());
    }

    @Test
    void disabledAiPlansRejectPlanAndDoNotFallbackToLocalPlanning() {
        when(systemFeatures.aiPlansEnabled()).thenReturn(false);
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "PLAN",
                new AiContracts.Action("module_fs", "fs.mkdir", Map.of("path", "归档"))
        ));

        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "整理目录",
                null,
                "disabled-plan-request"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE");
                });
        verifyNoInteractions(actions, plans);
    }

    @Test
    void sendsOnlyEnabledModulesAndDynamicallyEnabledActionsAndQueriesToPython() {
        ModuleContract enabled = new ModuleContract(
                "module_enabled",
                "启用模块",
                "已启用",
                1,
                null,
                List.of(
                        action("action.allowed"),
                        action("action.dynamic-off")
                ),
                List.of(
                        query("query.allowed"),
                        query("query.dynamic-off")
                ),
                List.of()
        );
        ModuleContract disabled = new ModuleContract(
                "module_disabled",
                "停用模块",
                "已停用",
                2,
                null,
                List.of(action("disabled.action")),
                List.of(query("disabled.query")),
                List.of()
        );
        when(catalog.businessModules()).thenReturn(List.of(enabled, disabled));
        when(moduleControl.isEnabled("module_enabled")).thenReturn(true);
        when(moduleControl.isEnabled("module_disabled")).thenReturn(false);
        when(moduleControl.isActionAiCallableEnabled("module_enabled", "action.allowed"))
                .thenReturn(true);
        when(moduleControl.isActionAiCallableEnabled(
                "module_enabled",
                "action.dynamic-off"
        )).thenReturn(false);
        when(moduleControl.isQueryAiCallableEnabled("module_enabled", "query.allowed"))
                .thenReturn(true);
        when(moduleControl.isQueryAiCallableEnabled(
                "module_enabled",
                "query.dynamic-off"
        )).thenReturn(false);
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "QUESTION",
                new AiContracts.Question("继续？", List.of())
        ));

        service.interpret(new AiContracts.TurnRequest(
                "读取允许接口",
                null,
                "filtered-tools-request"
        ));

        ArgumentCaptor<AiContracts.ServiceTurnRequest> captor =
                ArgumentCaptor.forClass(AiContracts.ServiceTurnRequest.class);
        verify(aiService).createTurn(captor.capture(), any());
        assertThat(captor.getValue().modules()).singleElement().satisfies(module -> {
            assertThat(module.moduleId()).isEqualTo("module_enabled");
            assertThat(module.actions()).extracting(AiContracts.ToolAction::actionId)
                    .containsExactly("action.allowed");
            assertThat(module.queries()).extracting(AiContracts.ToolQuery::queryId)
                    .containsExactly("query.allowed");
        });
    }

    @Test
    void sendsWhitelistedSystemControlInterfacesToPython() {
        ModuleContract control = new ModuleContract(
                "module_system_control",
                "模组设置",
                "系统治理",
                10_000,
                new ModuleSystemMetadata(
                        "MODULE_CONTROL",
                        true,
                        List.of("MODULE_GOVERNANCE")
                ),
                List.of(
                        action("module_system_control.module.set_enabled"),
                        action("module_system_control.interface.set_callable")
                ),
                List.of(
                        query("module_system_control.module.list"),
                        query("module_system_control.interface.list"),
                        query("module_system_control.setting.get")
                ),
                List.of()
        );
        when(catalog.systemModules()).thenReturn(List.of(control));
        when(moduleControl.isActionAiCallableEnabled(any(), any())).thenReturn(true);
        when(moduleControl.isQueryAiCallableEnabled(any(), any())).thenReturn(true);
        when(aiService.createTurn(any(), any())).thenReturn(decision(
                "QUESTION",
                new AiContracts.Question("继续？", List.of())
        ));

        service.interpret(new AiContracts.TurnRequest(
                "管理模块",
                null,
                "system-control-tools"
        ));

        ArgumentCaptor<AiContracts.ServiceTurnRequest> captor =
                ArgumentCaptor.forClass(AiContracts.ServiceTurnRequest.class);
        verify(aiService).createTurn(captor.capture(), any());
        assertThat(captor.getValue().modules()).singleElement().satisfies(module -> {
            assertThat(module.moduleId()).isEqualTo("module_system_control");
            assertThat(module.actions()).extracting(AiContracts.ToolAction::actionId)
                    .containsExactly(
                            "module_system_control.module.set_enabled",
                            "module_system_control.interface.set_callable"
                    );
            assertThat(module.queries()).extracting(AiContracts.ToolQuery::queryId)
                    .containsExactly(
                            "module_system_control.module.list",
                            "module_system_control.interface.list",
                            "module_system_control.setting.get"
                    );
        });
    }

    private AiContracts.SettingsView settings(
            boolean allowDeleteDirect,
            boolean allowSendQueryResultsToModel
    ) {
        return settings(allowDeleteDirect, allowSendQueryResultsToModel, 0);
    }

    private AiContracts.SettingsView settings(
            boolean allowDeleteDirect,
            boolean allowSendQueryResultsToModel,
            int settingsVersion
    ) {
        return new AiContracts.SettingsView(
                AiContracts.Mode.DEEPSEEK,
                "https://api.deepseek.com",
                "deepseek-flash",
                true,
                allowDeleteDirect,
                allowSendQueryResultsToModel,
                false,
                settingsVersion
        );
    }

    private AiContracts.ServiceDecision queryDecision() {
        return new AiContracts.ServiceDecision(
                "QUERY",
                "conversation-query",
                "需要读取本地数据",
                null,
                List.of(new AiContracts.QueryCall(
                        "module_todo",
                        "todo.list",
                        Map.of()
                )),
                null
        );
    }

    private AiContracts.QueryResult okQueryResult() {
        return new AiContracts.QueryResult(
                "module_todo",
                "todo.list",
                AiContracts.QueryStatus.OK,
                Map.of("items", List.of(Map.of(
                        "title", "项目周报",
                        "status", "OPEN"
                ))),
                "查询完成"
        );
    }

    private ActionContract action(String id) {
        return new ActionContract(
                id,
                id,
                "动作说明",
                RiskLevel.READ_ONLY,
                false,
                null,
                List.of(),
                null
        );
    }

    private QueryContract query(String id) {
        return new QueryContract(
                id,
                id,
                "查询说明",
                List.of()
        );
    }

    private AiContracts.ServiceDecision decision(
            String kind,
            AiContracts.Action action
    ) {
        return new AiContracts.ServiceDecision(
                kind,
                "conversation-" + kind.toLowerCase(),
                "message",
                List.of(action),
                null,
                null
        );
    }

    private AiContracts.ServiceDecision decision(
            String kind,
            AiContracts.Question question
    ) {
        return new AiContracts.ServiceDecision(
                kind,
                "conversation-" + kind.toLowerCase(),
                "message",
                null,
                null,
                question
        );
    }

    private AiActionService.Preview preview(RiskLevel risk, String actionId) {
        return new AiActionService.Preview(
                List.of(new AiActionService.ActionCall(
                        "module_fs",
                        actionId,
                        Map.of("path", "目标")
                )),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        actionId,
                        "动作",
                        "动作说明",
                        risk,
                        Map.of("path", "目标")
                )),
                risk
        );
    }
}
