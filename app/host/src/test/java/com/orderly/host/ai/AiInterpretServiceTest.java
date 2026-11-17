package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiInterpretServiceTest {

    @Mock
    private AiSettingsService settings;
    @Mock
    private AiProviderClient client;
    @Mock
    private AiPromptFactory prompts;
    @Mock
    private ContractCatalog catalog;
    @Mock
    private ModuleControlService moduleControl;
    @Mock
    private AiActionService actions;
    @Mock
    private AiPlanService plans;

    private final AiContextEpoch contextEpoch = new AiContextEpoch();
    private AiInterpretService service;

    @BeforeEach
    void setUp() {
        service = new AiInterpretService(
                settings,
                client,
                prompts,
                catalog,
                moduleControl,
                actions,
                plans,
                new ObjectMapper(),
                contextEpoch
        );
        lenient().when(settings.requireConfigured()).thenReturn(new AiProviderConfig(
                AiContracts.Mode.DEEPSEEK,
                AiSettingsService.DEEPSEEK_BASE_URL,
                AiSettingsService.DEEPSEEK_MODEL,
                "temporary-key",
                false
        ));
        lenient().when(prompts.systemPrompt(anyList(), anyBoolean())).thenReturn("system");
        lenient().when(catalog.businessModules()).thenReturn(List.of());
    }

    @Test
    void returnsQuestionWithoutExecution() {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"QUESTION","message":"请选择目录","question":{"prompt":"哪个目录？","options":["下载目录","桌面"]}}
                """);

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "整理一下",
                null,
                "question-request"
        ));

        assertThat(response.kind()).isEqualTo("QUESTION");
        assertThat(response.question().options()).containsExactly("下载目录", "桌面");
        assertThat(response.execution()).isNull();
    }

    @Test
    void defaultsDeleteActionsToIndependentPlan() {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"ACTIONS","message":"删除","actions":[{"moduleId":"module_fs","actionId":"fs.trash","arguments":{"path":"旧文件.txt"}}]}
                """);
        AiActionService.Preview preview = new AiActionService.Preview(
                List.of(new AiActionService.ActionCall("module_fs", "fs.trash", Map.of("path", "旧文件.txt"))),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.trash",
                        "移入回收站",
                        "将对象移入项目回收站",
                        RiskLevel.DELETE,
                        Map.of("path", "旧文件.txt")
                )),
                RiskLevel.DELETE
        );
        when(actions.preview(anyList())).thenReturn(preview);
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
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"ACTIONS","message":"创建","actions":[{"moduleId":"module_fs","actionId":"fs.mkdir","arguments":{"path":"归档"}}]}
                """);
        when(actions.preview(anyList())).thenReturn(new AiActionService.Preview(
                List.of(new AiActionService.ActionCall("module_fs", "fs.mkdir", Map.of("path", "归档"))),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.mkdir",
                        "创建目录",
                        "创建目录",
                        RiskLevel.WRITE,
                        Map.of("path", "归档")
                )),
                RiskLevel.WRITE
        ));
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
    void providerFailureNeverFallsBackToLocalPlanning() {
        when(client.complete(any(), any(), anyList()))
                .thenThrow(new AiProviderException("模型服务不可用"));

        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "整理目录",
                null,
                "provider-failure-request"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
                });
        verifyNoInteractions(actions, plans);
    }

    @Test
    void requiresClientRequestIdBeforeCallingTheModel() {
        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "创建归档目录",
                null,
                null
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("AI_CLIENT_REQUEST_ID_REQUIRED");
                });
        verifyNoInteractions(client, actions, plans);
    }

    @Test
    void executesDeleteDirectlyOnlyWhenTheHostSettingAllowsIt() {
        when(settings.requireConfigured()).thenReturn(new AiProviderConfig(
                AiContracts.Mode.DEEPSEEK,
                AiSettingsService.DEEPSEEK_BASE_URL,
                AiSettingsService.DEEPSEEK_MODEL,
                "temporary-key",
                true
        ));
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"ACTIONS","message":"删除","actions":[{"moduleId":"module_fs","actionId":"fs.trash","arguments":{"path":"旧文件.txt"}}]}
                """);
        when(actions.preview(anyList())).thenReturn(new AiActionService.Preview(
                List.of(new AiActionService.ActionCall(
                        "module_fs",
                        "fs.trash",
                        Map.of("path", "旧文件.txt")
                )),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.trash",
                        "移入回收站",
                        "将对象移入项目回收站",
                        RiskLevel.DELETE,
                        Map.of("path", "旧文件.txt")
                )),
                RiskLevel.DELETE
        ));
        ExecutionRecord record = mock(ExecutionRecord.class);
        when(actions.executeAiDirect(anyList())).thenReturn(record);

        AiContracts.TurnResponse response = service.interpret(new AiContracts.TurnRequest(
                "删除旧文件.txt",
                null,
                "delete-direct-request"
        ));

        assertThat(response.kind()).isEqualTo("ACTIONS");
        assertThat(response.highestRisk()).isEqualTo(RiskLevel.DELETE);
        assertThat(response.execution()).isSameAs(record);
        verifyNoInteractions(plans);
    }

    @Test
    void replayedClientRequestReturnsTheFirstResultWithoutExecutingTwice() {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"QUESTION","message":"请选择目录","question":{"prompt":"哪个目录？","options":[]}}
                """);
        AiContracts.TurnRequest request = new AiContracts.TurnRequest(
                "整理目录",
                null,
                "client-request-1"
        );

        AiContracts.TurnResponse first = service.interpret(request);
        AiContracts.TurnResponse replay = service.interpret(request);

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        verify(client, times(1)).complete(any(), any(), anyList());
    }

    @Test
    void reusingClientRequestIdForDifferentGoalsIsRejected() {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {"kind":"QUESTION","message":"请选择目录","question":{"prompt":"哪个目录？","options":[]}}
                """);
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
        verify(client, times(1)).complete(any(), any(), anyList());
    }

    @Test
    void providerChangeDuringModelCallPreventsExecution() {
        when(client.complete(any(), any(), anyList())).thenAnswer(invocation -> {
            contextEpoch.advance();
            return """
                    {"kind":"ACTIONS","message":"创建","actions":[{"moduleId":"module_fs","actionId":"fs.mkdir","arguments":{"path":"归档"}}]}
                    """;
        });

        assertThatThrownBy(() -> service.interpret(new AiContracts.TurnRequest(
                "创建归档目录",
                null,
                "client-request-change"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("AI_SETTINGS_CHANGED");
                });
        verifyNoInteractions(actions, plans);
    }
}
