package com.orderly.host;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanStatus;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void exposesHealthAndContractCatalog() throws Exception {
        assertThat(catalog.modules()).hasSize(3);
        assertThat(catalog.modules().stream()
                .flatMap(module -> module.actions().stream()).toList()).hasSize(7);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.planner").value("LOCAL_RULES"));
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
    void deleteOnlyMovesToTrashAndRequiresPhrase() {
        PlanView plan = plans.create("删除 待整理/草案.txt");
        assertThatThrownBy(() -> execution.confirm(plan.planId(), true, "确认"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("确认词");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isTrue();

        ExecutionRunResult result = execution.confirm(plan.planId(), true, "删除");
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isFalse();
        assertThat(runtime.listTrash()).anyMatch(entry ->
                entry.originalPath().equals("待整理/草案.txt"));

        execution.rollback(plan.planId());
        assertThat(runtime.exists(runtime.resolveWorkspacePath("待整理/草案.txt"))).isTrue();
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
}
