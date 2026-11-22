package com.orderly.host;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.execution.ExecutionRecordStore;
import com.orderly.host.execution.ExecutionRecordStep;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanStatus;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.system.SystemFeatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 首版端到端核心链路测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderlyIntegrationTest {

    private static final String TEST_DATA = "target/test-data-" + UUID.randomUUID();

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> TEST_DATA);
        registry.add("orderly.themes-dir", () -> System.getProperty("orderly.themes-dir"));
    }

    @Autowired
    private ContractCatalog catalog;

    @Autowired
    private PlanService plans;

    @Autowired
    private ExecutionService execution;

    @Autowired
    private WorkspaceRuntime runtime;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExecutionJournal journal;

    @Autowired
    private ExecutionRecordStore executionRecords;

    @Autowired
    private SystemFeatureService features;

    @Autowired
    private ModuleControlService moduleControl;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void enablePlanning() {
        features.setPlanningEnabled(true);
    }

    @Test
    void exposesHealthAndContractCatalog() throws Exception {
        assertThat(catalog.businessModules())
                .extracting(module -> module.moduleId())
                .doesNotHaveDuplicates();
        assertThat(catalog.systemModules())
                .extracting(module -> module.moduleId())
                .containsExactly("module_system_control");

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.planner").value("LOCAL_RULES"));
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.javaVersion").value(21));
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/system-modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moduleId").value("module_system_control"))
                .andExpect(jsonPath("$[0].system.role").value("MODULE_CONTROL"))
                .andExpect(jsonPath("$[0].enabled").value(true));
        mockMvc.perform(get("/api/themes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("paper"))
                .andExpect(jsonPath("$.items[0].schemaVersion").value(1))
                .andExpect(jsonPath("$.items[0].default").value(true))
                .andExpect(jsonPath("$.items[0].label").value("纸页"))
                .andExpect(jsonPath("$.items[0].note").value("温暖明亮"))
                .andExpect(jsonPath("$.items[0].order").value(10))
                .andExpect(jsonPath("$.issues").isArray())
                .andExpect(jsonPath("$.builtInFallbackActive").value(false));
        mockMvc.perform(get("/api/themes/paper"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("paper"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.styles[0]").value("tokens.css"))
                .andExpect(jsonPath("$.styles[1]").value("motion.css"))
                .andExpect(jsonPath("$.assets").isEmpty())
                .andExpect(jsonPath("$.rootPath").doesNotExist());
        mockMvc.perform(get("/api/themes/paper/assets/tokens.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/css")))
                .andExpect(content().string(containsString("@layer orderly.theme")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "no-cache"));
        mockMvc.perform(get("/api/themes/paper/assets/assets/paper-grain.svg"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/themes/paper/assets/theme.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEME_ASSET_NOT_FOUND"));
        mockMvc.perform(get("/api/themes/paper/assets/theme.js"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/themes/paper/assets/%2e%2e/theme.json"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/themes/not-present"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEME_NOT_FOUND"));
        mockMvc.perform(post("/api/plans")
                        .contentType("application/json")
                        .content("{\"goal\":\"创建目录 演示归档\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PLAN"))
                .andExpect(jsonPath("$.steps[0].actionId").value("fs.mkdir"));
    }

    @Test
    void exposesOnlyContractDeclaredModuleQueriesAndUiAssets() throws Exception {
        mockMvc.perform(get("/api/modules/module_fs/query/fs.summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.entries.length()").value(2));
        mockMvc.perform(get("/api/modules/module_todo/query/todo.list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(get("/api/modules/module_calendar/query/calendar.list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("项目评审"));

        mockMvc.perform(get("/api/modules/module_fs/query/fs.list")
                        .param("path", "收件箱"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("收件箱"))
                .andExpect(jsonPath("$.entries[0].name").value("示例笔记.txt"));

        mockMvc.perform(get("/api/modules/module_fs/query/todo.list"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_QUERY_NOT_FOUND"));
        mockMvc.perform(get("/api/modules/not_installed/query/not.query"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_FOUND"));
        mockMvc.perform(get("/api/modules/module_fs/query/fs.list")
                        .param("outside", "../escape"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETERS"));

        mockMvc.perform(get("/api/modules/module_fs/ui/module.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.view.id").value("files"));
        mockMvc.perform(get("/api/modules/module_fs/ui/module.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/javascript")));
        mockMvc.perform(get("/api/modules/module_fs/ui/module.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/css")));
        mockMvc.perform(get("/api/modules/module_fs/ui/../module.json"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/modules/module_fs/ui/unknown.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_UI_NOT_FOUND"));
    }

    @Test
    void fixedBusinessReadEndpointsAreRemoved() throws Exception {
        mockMvc.perform(get("/api/files")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/todos")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/calendar")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/trash")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnconfirmedWriteAndProducesQuestionForUnknownGoal() {
        PlanView plan = plans.create("创建目录 拒绝执行目录");
        assertThatThrownBy(() -> execution.confirm(plan.planId(), false, ""))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须确认");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("拒绝执行目录"))).isFalse();

        PlanView question = plans.create("帮我写一篇散文");
        assertThat(question.kind()).isEqualTo("QUESTION");
        assertThat(question.steps()).isEmpty();
    }

    @Test
    void planningFeatureGateKeepsExecutionHistoryReadable() throws Exception {
        PlanView plan = plans.create("创建目录 功能关闭测试");
        execution.confirm(plan.planId(), true, "");
        features.setPlanningEnabled(false);
        try {
            mockMvc.perform(post("/api/plans")
                            .contentType("application/json")
                            .content("{\"goal\":\"创建目录 功能关闭后的计划\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
            mockMvc.perform(post("/api/plans/{planId}/confirm", plan.planId())
                            .contentType("application/json")
                            .content("{\"confirmed\":true}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
            mockMvc.perform(post("/api/plans/{planId}/rollback", plan.planId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
            mockMvc.perform(get("/api/executions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.executionId=='" + plan.planId() + "')]").exists())
                    .andExpect(jsonPath("$[?(@.origin=='PLAN')]").exists());
            mockMvc.perform(get("/api/executions/{id}", plan.planId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.origin").value("PLAN"))
                    .andExpect(jsonPath("$.snapshots").isMap())
                    .andExpect(jsonPath("$.events").isArray());
        } finally {
            features.setPlanningEnabled(true);
            execution.rollback(plan.planId());
        }
    }

    @Test
    void rejectsUnconfirmedAiDeleteBeforeAnyMutation() throws Exception {
        mockMvc.perform(post("/api/ai/actions/execute")
                        .contentType("application/json")
                        .content("""
                                {
                                  "confirmed": false,
                                  "actions": [{
                                    "moduleId": "module_fs",
                                    "actionId": "fs.trash",
                                    "arguments": {"path": "待整理/草案.txt"}
                                  }]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.details.highestRisk").value("DELETE"));
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isTrue();
    }

    @Test
    void rejectsDynamicallyDisabledAiAction() throws Exception {
        moduleControl.setInterfaceAiCallable("module_fs", "action", "fs.restore", false);
        try {
            mockMvc.perform(post("/api/ai/actions/execute")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "confirmed": true,
                                      "actions": [{
                                        "moduleId": "module_fs",
                                        "actionId": "fs.restore",
                                        "arguments": {"trashId": "not-used"}
                                      }]
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACTION_AI_DISABLED"));
        } finally {
            moduleControl.setInterfaceAiCallable("module_fs", "action", "fs.restore", true);
        }
    }

    @Test
    void moduleControlActionsUseAuditedSourceWhenAiIsDisabled() throws Exception {
        moduleControl.setInterfaceAiCallable(
                "module_calendar",
                "action",
                "calendar.space.create",
                false
        );
        try {
            clearCalendarSpaces();
            mockMvc.perform(post("/api/modules/module_calendar/actions/calendar.space.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmed": true,
                                  "arguments": {
                                    "type": "text",
                                    "text": "这是一个模块控制创建的空间"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("MODULE_CONTROL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.events").isArray());

        MvcResult spacesResult = mockMvc.perform(
                        get("/api/modules/module_calendar/query/calendar.space.list")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].type").value("text"))
                .andReturn();
        String spaceId = objectMapper.readTree(spacesResult.getResponse().getContentAsString())
                .path("items")
                .get(0)
                .path("id")
                .asText();

        mockMvc.perform(post("/api/modules/module_calendar/actions/calendar.space.delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmed": true,
                                  "arguments": {"spaceId": "%s"}
                                }
                                """.formatted(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("MODULE_CONTROL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

            mockMvc.perform(get("/api/modules/module_calendar/query/calendar.space.list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
            assertThat(runtime.listTrash()).anyMatch(entry ->
                    entry.originalPath().startsWith("modules/module_calendar/spaces/"));
        } finally {
            moduleControl.setInterfaceAiCallable(
                    "module_calendar",
                    "action",
                    "calendar.space.create",
                    true
            );
        }
    }

    @Test
    void moduleControlRejectsUnconfirmedWriteAndAiCannotOperateSpaces() throws Exception {
        mockMvc.perform(post("/api/modules/module_calendar/actions/calendar.space.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmed": false,
                                  "arguments": {"type": "text", "text": "拒绝写入"}
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        moduleControl.setInterfaceAiCallable(
                "module_calendar",
                "action",
                "calendar.space.create",
                false
        );
        try {
            mockMvc.perform(post("/api/ai/actions/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "confirmed": true,
                                      "actions": [{
                                        "moduleId": "module_calendar",
                                        "actionId": "calendar.space.create",
                                        "arguments": {"type": "text", "text": "AI 不允许创建"}
                                      }]
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACTION_AI_DISABLED"));
        } finally {
            moduleControl.setInterfaceAiCallable(
                    "module_calendar",
                    "action",
                    "calendar.space.create",
                    true
            );
        }
    }

    @Test
    void moduleControlUploadStagesMediaAndServesItFromModuleData() throws Exception {
        clearCalendarSpaces();
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        MockMultipartFile request = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "confirmed": true,
                          "arguments": {
                            "type": "image",
                            "uploadPath": "uploads/integration.png",
                            "contentType": "image/png"
                          }
                        }
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "integration.png",
                "image/png",
                png
        );
        mockMvc.perform(multipart(
                                "/api/modules/module_calendar/actions/calendar.space.create/upload"
                        )
                        .file(request)
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("MODULE_CONTROL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        MvcResult spacesResult = mockMvc.perform(
                        get("/api/modules/module_calendar/query/calendar.space.list")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].type").value("image"))
                .andReturn();
        String mediaPath = objectMapper.readTree(spacesResult.getResponse().getContentAsString())
                .path("items")
                .get(0)
                .path("mediaPath")
                .asText();

        mockMvc.perform(get("/api/modules/module_calendar/data/" + mediaPath))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
    }

    @Test
    void moduleControlUploadRejectsTraversalAndUnsupportedMedia() throws Exception {
        MockMultipartFile traversalRequest = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "confirmed": true,
                          "arguments": {
                            "type": "image",
                            "uploadPath": "../escape.png",
                            "contentType": "image/png"
                          }
                        }
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        MockMultipartFile png = new MockMultipartFile(
                "file",
                "escape.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}
        );
        mockMvc.perform(multipart(
                                "/api/modules/module_calendar/actions/calendar.space.create/upload"
                        )
                        .file(traversalRequest)
                        .file(png))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODULE_DATA_PATH"));

        MockMultipartFile textRequest = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "confirmed": true,
                          "arguments": {
                            "type": "image",
                            "uploadPath": "uploads/unsupported.txt",
                            "contentType": "text/plain"
                          }
                        }
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        MockMultipartFile text = new MockMultipartFile(
                "file",
                "unsupported.txt",
                "text/plain",
                "not-media".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart(
                                "/api/modules/module_calendar/actions/calendar.space.create/upload"
                        )
                        .file(textRequest)
                        .file(text))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MODULE_DATA_TYPE_UNSUPPORTED"));
    }

    @Test
    void executesAiActionsWithStructuredReferenceAndRollsBackRecord() throws Exception {
        int before = runtime.readItems("todos").size();
        MvcResult result = mockMvc.perform(post("/api/ai/actions/execute")
                        .contentType("application/json")
                        .content("""
                                {
                                  "confirmed": true,
                                  "actions": [
                                    {
                                      "moduleId": "module_fs",
                                      "actionId": "fs.list",
                                      "arguments": {"path": "收件箱"}
                                    },
                                    {
                                      "moduleId": "module_todo",
                                      "actionId": "todo.create",
                                      "arguments": {
                                        "title": {
                                          "$fromAction": 0,
                                          "path": ["output", "entries", 0, "path"]
                                        }
                                      }
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("AI_DIRECT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[1].arguments.title").value("收件箱/示例笔记.txt"))
                .andExpect(jsonPath("$.snapshots").isMap())
                .andExpect(jsonPath("$.outputs").isMap())
                .andExpect(jsonPath("$.events").isArray())
                .andReturn();
        String executionId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("executionId")
                .asText();
        assertThat(executionId).isNotBlank();
        assertThat(runtime.readItems("todos")).hasSize(before + 1);
        assertThat(runtime.readItems("todos"))
                .anyMatch(item -> "收件箱/示例笔记.txt".equals(item.get("title")));

        mockMvc.perform(post("/api/executions/{id}/rollback", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.origin").value("AI_DIRECT"));
        assertThat(runtime.readItems("todos")).hasSize(before);
    }

    @Test
    void supportsTheFrontendExamples() {
        PlanView archive = plans.create("创建目录 归档，然后移动 收件箱/示例笔记.txt 到 归档");
        assertThat(archive.steps()).hasSize(2);
        assertThat(archive.steps().getFirst().arguments()).containsEntry("path", "归档");

        PlanView todo = plans.create("创建待办：提交周报；截止：2026-11-20");
        assertThat(todo.steps()).hasSize(1);
        assertThat(todo.steps().getFirst().arguments())
                .containsEntry("title", "提交周报")
                .containsEntry("dueAt", "2026-11-20");

        PlanView calendar = plans.create("把日程 项目评审 改到 2026-11-20 15:00");
        assertThat(calendar.steps()).hasSize(1);
        assertThat(calendar.steps().getFirst().arguments())
                .containsEntry("title", "项目评审")
                .containsEntry("newStartAt", "2026-11-20 15:00");
    }

    @Test
    void confirmResponseSupportsFrontendStatusContract() throws Exception {
        PlanView plan = plans.create("创建目录 HTTP状态字段目录");
        mockMvc.perform(post("/api/plans/{planId}/confirm", plan.planId())
                        .contentType("application/json")
                        .content("{\"confirmed\":true,\"confirmation\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.plan.status").value("COMPLETED"));
        execution.rollback(plan.planId());
    }

    @Test
    void movesFileAndRollsBackInReverse() {
        PlanView plan = plans.create("移动 收件箱/示例笔记.txt 到 待整理/示例笔记.txt");
        ExecutionRunResult result = execution.confirm(plan.planId(), true, "");
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("收件箱/示例笔记.txt"))).isFalse();
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/示例笔记.txt"))).isTrue();

        ExecutionRunResult rollback = execution.rollback(plan.planId());
        assertThat(rollback.plan().status()).isEqualTo(PlanStatus.ROLLED_BACK.name());
        assertThat(runtime.exists(runtime.resolveWorkspacePath("收件箱/示例笔记.txt"))).isTrue();
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/示例笔记.txt"))).isFalse();
    }

    @Test
    void deleteOnlyMovesToTrashAndRequiresRiskConfirmation() {
        PlanView plan = plans.create("删除 待整理/草案.txt");
        assertThatThrownBy(() -> execution.confirm(plan.planId(), false, ""))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须确认");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isTrue();

        ExecutionRunResult result = execution.confirm(plan.planId(), true, "");
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isFalse();
        assertThat(runtime.listTrash()).anyMatch(entry ->
                entry.originalPath().equals("待整理/草案.txt"));

        execution.rollback(plan.planId());
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isTrue();
    }

    @Test
    void failedPlanPersistsFailedStepAndRolledBackOutcome() {
        PlanView plan = plans.create("移动 不存在/缺少.txt 到 目标/缺少.txt");
        ExecutionRunResult result = execution.confirm(plan.planId(), true, "");
        assertThat(result.outcome()).isEqualTo("FAILED_ROLLED_BACK");

        ExecutionRecord record = executionRecords.require(plan.planId());
        assertThat(record.origin()).isEqualTo("PLAN");
        assertThat(record.status()).isEqualTo("FAILED_ROLLED_BACK");
        assertThat(record.steps()).singleElement()
                .extracting(ExecutionRecordStep::status)
                .isEqualTo("FAILED");
    }

    @Test
    void createsAndRollsBackTodoAndCalendarChange() {
        int todoCount = runtime.readItems("todos").size();
        PlanView todoPlan = plans.create("创建待办 集成测试事项 截止 2026-12-01");
        execution.confirm(todoPlan.planId(), true, "");
        assertThat(runtime.readItems("todos")).hasSize(todoCount + 1);
        execution.rollback(todoPlan.planId());
        assertThat(runtime.readItems("todos")).hasSize(todoCount);

        List<Map<String, Object>> before = runtime.readItems("calendar");
        String originalStart = before.stream()
                .filter(item -> "项目评审".equals(item.get("title")))
                .map(item -> String.valueOf(item.get("startAt")))
                .findFirst()
                .orElseThrow();
        PlanView calendarPlan = plans.create(
                "修改日程 项目评审 改到 2026-12-05T09:00:00+08:00"
        );
        execution.confirm(calendarPlan.planId(), true, "");
        assertThat(runtime.readItems("calendar"))
                .anyMatch(item -> "2026-12-05T09:00:00+08:00".equals(item.get("startAt")));
        execution.rollback(calendarPlan.planId());
        assertThat(runtime.readItems("calendar"))
                .anyMatch(item -> originalStart.equals(item.get("startAt")));
    }

    @Test
    void rejectsWorkspaceTraversal() {
        assertThatThrownBy(() -> runtime.resolveWorkspacePath("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越过工作区");
        assertThatThrownBy(() -> runtime.resolveWorkspacePath("C:\\Windows\\win.ini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相对路径");
        assertThatThrownBy(() -> runtime.moveToTrash(runtime.workspaceRoot(), "test", "step"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("整个工作区");
    }

    @Test
    void persistsAuditEventsWithMonotonicSequence() {
        assertThat(runtime.dataRoot().resolve("events.jsonl")).exists();
        List<Long> sequences = journal.snapshot().stream().map(event -> event.seq()).toList();
        assertThat(sequences).isSorted();
        assertThat(sequences).doesNotHaveDuplicates();
    }

    private void clearCalendarSpaces() {
        var root = runtime.resolveWorkspacePath("modules/module_calendar/spaces");
        if (!runtime.exists(root)) {
            return;
        }
        for (var entry : runtime.listDirectory(root)) {
            runtime.moveToTrash(
                    runtime.resolveWorkspacePath("modules/module_calendar/spaces/" + entry.name()),
                    "test-cleanup",
                    entry.name()
            );
        }
    }
}
