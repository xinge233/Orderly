package com.orderly.host.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private AiProviderClient client;

    @BeforeEach
    void configureTemporaryKey() throws Exception {
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
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @AfterEach
    void clearTemporaryKey() throws Exception {
        mockMvc.perform(delete("/api/ai/settings"))
                .andExpect(status().isOk());
    }

    @Test
    void naturalLanguageQuestionDoesNotExecuteAnything() throws Exception {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {
                  "kind": "QUESTION",
                  "message": "需要选择目录",
                  "question": {
                    "prompt": "要整理哪个目录？",
                    "options": ["下载目录", "桌面"]
                  }
                }
                """);

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
    void defaultPlanUsesIndependentAiPlanShape() throws Exception {
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {
                  "kind": "PLAN",
                  "message": "请审查计划",
                  "actions": [
                    {
                      "moduleId": "module_fs",
                      "actionId": "fs.mkdir",
                      "arguments": {"path": "AI集成验收目录"}
                    }
                  ]
                }
                """);

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
    void customHttpUrlIsRejectedWithoutDevelopmentSwitch() throws Exception {
        mockMvc.perform(put("/api/ai/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "CUSTOM",
                                  "apiKey": "temporary-integration-key",
                                  "baseUrl": "http://example.com/v1",
                                  "model": "custom-model",
                                  "allowDeleteDirect": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_INVALID_BASE_URL"));
    }

    @Test
    void confirmedAiPlanExecutesAndRollsBackThroughTheHostCore() throws Exception {
        String directory = "ai-plan-" + java.util.UUID.randomUUID();
        when(client.complete(any(), any(), anyList())).thenReturn("""
                {
                  "kind": "PLAN",
                  "message": "请审查计划",
                  "actions": [
                    {
                      "moduleId": "module_fs",
                      "actionId": "fs.mkdir",
                      "arguments": {"path": "%s"}
                    }
                  ]
                }
                """.formatted(directory));

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
        String executionId = objectMapper.readTree(executionBody).path("executionId").asText();

        mockMvc.perform(post("/api/executions/{executionId}/rollback", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
    }
}
