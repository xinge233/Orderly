package com.orderly.host.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiServiceClient aiService;

    @BeforeEach
    void configureGateway() {
        AiContracts.SettingsView settings = settings();
        when(aiService.settings()).thenReturn(settings);
        when(aiService.settings(any())).thenReturn(settings);
        when(aiService.beginTurn()).thenReturn(
                new AiServiceClient.TurnBudget(Instant.now().plusSeconds(60))
        );
        when(aiService.updateSettings(any())).thenReturn(settings);
        when(aiService.clearSettings()).thenReturn(settings());
        when(aiService.testSettings()).thenReturn(new AiContracts.ConnectionTest(true, "连接成功"));
        when(aiService.health()).thenReturn("UP");
        when(aiService.configured()).thenReturn(true);
    }

    @Test
    void settingsAndTurnsAreProxiedWithoutJavaProviderState() throws Exception {
        mockMvc.perform(get("/api/ai/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.allowSendQueryResultsToModel").doesNotExist());
        mockMvc.perform(put("/api/ai/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "DEEPSEEK",
                                  "apiKey": "temporary-integration-key",
                                  "baseUrl": null,
                                  "model": null,
                                  "allowDeleteDirect": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        mockMvc.perform(post("/api/ai/settings/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("连接成功"));
        mockMvc.perform(delete("/api/ai/settings"))
                .andExpect(status().isOk());
    }

    @Test
    void healthExposesOnlyNonSensitiveAiServiceStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiService").value("UP"))
                .andExpect(jsonPath("$.aiConfigured").value(true));
    }

    @Test
    void naturalLanguageQuestionDoesNotExecuteAnything() throws Exception {
        when(aiService.createTurn(any(), any())).thenReturn(new AiContracts.ServiceDecision(
                "QUESTION",
                "integration-conversation",
                "需要选择目录",
                null,
                null,
                new AiContracts.Question(
                        "要整理哪个目录？",
                        List.of("下载目录", "桌面")
                )
        ));

        mockMvc.perform(post("/api/ai/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "整理一下目录",
                                  "conversationId": null,
                                  "clientRequestId": "integration-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.question.prompt").value("要整理哪个目录？"))
                .andExpect(jsonPath("$.execution").doesNotExist());
    }

    @Test
    void queryResultsCanBeRenderedByAValidatedView() throws Exception {
        when(aiService.createTurn(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "QUERY",
                        "integration-query-conversation",
                        "需要读取日程",
                        null,
                        List.of(new AiContracts.QueryCall(
                                "module_calendar",
                                "calendar.list",
                                java.util.Map.of()
                        )),
                        null
                )
        );
        when(aiService.continueWithQueryResults(any(), any())).thenReturn(
                new AiContracts.ServiceDecision(
                        "VIEW",
                        "integration-query-conversation",
                        "已整理日程",
                        null,
                        null,
                        null,
                        new AiContracts.QueryView(
                                1,
                                "list",
                                "日程列表",
                                "items",
                                List.of(
                                        new AiContracts.ViewField("title", "标题", "text"),
                                        new AiContracts.ViewField("status", "状态", "status")
                                ),
                                "暂无日程"
                        )
                )
        );

        mockMvc.perform(post("/api/ai/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "查看日程",
                                  "conversationId": null,
                                  "clientRequestId": "integration-query-view"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("VIEW"))
                .andExpect(jsonPath("$.view.title").value("日程列表"))
                .andExpect(jsonPath("$.view.fields[0].key").value("title"))
                .andExpect(jsonPath("$.queryResults[0].status").value("OK"));
    }

    @Test
    void defaultPlanUsesIndependentAiPlanShape() throws Exception {
        when(aiService.createTurn(any(), any())).thenReturn(new AiContracts.ServiceDecision(
                "PLAN",
                "integration-plan-conversation",
                "请审查计划",
                List.of(new AiContracts.Action(
                        "module_fs",
                        "fs.mkdir",
                        java.util.Map.of("path", "AI集成验收目录")
                )),
                null,
                null
        ));

        mockMvc.perform(post("/api/ai/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "创建 AI集成验收目录",
                                  "conversationId": null,
                                  "clientRequestId": "default-plan-integration"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PLAN"))
                .andExpect(jsonPath("$.plan.status").value("PREVIEW"))
                .andExpect(jsonPath("$.plan.steps[0].moduleId").value("module_fs"))
                .andExpect(jsonPath("$.execution").doesNotExist());
    }

    @Test
    void confirmedAiPlanExecutesAndRollsBackThroughTheHostCore() throws Exception {
        String directory = "ai-plan-" + java.util.UUID.randomUUID();
        when(aiService.createTurn(any(), any())).thenReturn(new AiContracts.ServiceDecision(
                "PLAN",
                "integration-execution-conversation",
                "请审查计划",
                List.of(new AiContracts.Action(
                        "module_fs",
                        "fs.mkdir",
                        java.util.Map.of("path", directory)
                )),
                null,
                null
        ));

        String planBody = mockMvc.perform(post("/api/ai/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "创建 AI 计划验收目录",
                                  "conversationId": null,
                                  "clientRequestId": "ai-plan-integration"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PLAN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode turn = objectMapper.readTree(planBody);
        String planId = turn.path("plan").path("planId").asText();

        String executionBody = mockMvc.perform(post("/api/ai/plans/{planId}/confirm", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("AI_PLAN"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].snapshot").isMap())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String executionId = objectMapper.readTree(executionBody)
                .path("executionId")
                .asText();

        mockMvc.perform(post("/api/executions/{executionId}/rollback", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
    }

    private AiContracts.SettingsView settings() {
        return new AiContracts.SettingsView(
                AiContracts.Mode.DEEPSEEK,
                "https://api.deepseek.com",
                "deepseek-flash",
                true,
                false,
                false,
                0
        );
    }
}
