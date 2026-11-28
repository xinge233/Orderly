package com.orderly.host.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRecordStoreTest {

    @Test
    void persistsCompleteRecordAndReloadsFromAtomicJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        WorkspaceRuntime runtime = new WorkspaceRuntime(
                "target/execution-record-" + UUID.randomUUID(),
                objectMapper
        );
        runtime.initialize();
        ExecutionRecordStore store = new ExecutionRecordStore(objectMapper, runtime);
        store.initialize();

        Instant timestamp = Instant.parse("2026-11-15T02:03:04Z");
        ExecutionEvent event = new ExecutionEvent(
                1,
                "STEP",
                "AI_DIRECT",
                "execution-1",
                "a1",
                "module_todo",
                "todo.create",
                "COMPLETED",
                "完成",
                timestamp
        );
        ExecutionRecordStep step = new ExecutionRecordStep(
                "a1",
                "module_todo",
                "todo.create",
                "创建待办",
                RiskLevel.WRITE,
                Map.of("title", "记录测试"),
                "COMPLETED",
                Map.of("items", List.of()),
                Map.of("item", Map.of("id", "todo-1")),
                "完成"
        );
        ExecutionRecord record = new ExecutionRecord(
                "execution-1",
                "记录测试目标",
                "AI_DIRECT",
                "COMPLETED",
                List.of(step),
                Map.of("a1", step.arguments()),
                Map.of("a1", step.output()),
                Map.of("a1", step.snapshot()),
                List.of(event),
                timestamp,
                timestamp,
                timestamp,
                timestamp
        );
        store.save(record);

        ExecutionRecordStore reloaded = new ExecutionRecordStore(objectMapper, runtime);
        reloaded.initialize();
        ExecutionRecord loaded = reloaded.require("execution-1");
        assertThat(loaded.goal()).isEqualTo("记录测试目标");
        assertThat(loaded.origin()).isEqualTo("AI_DIRECT");
        assertThat(loaded.steps()).hasSize(1);
        assertThat(loaded.snapshots()).containsKey("a1");
        assertThat(loaded.outputs()).containsKey("a1");
        assertThat(loaded.events()).singleElement()
                .extracting(ExecutionEvent::origin)
                .isEqualTo("AI_DIRECT");
        ExecutionRecordSummary summary = reloaded.list().getFirst();
        assertThat(summary.goal()).isEqualTo("记录测试目标");
        assertThat(summary.stepCount()).isEqualTo(1);
        assertThat(summary.canRollback()).isTrue();
        assertThat(summary.rollbackDisabledReason()).isEmpty();

        JsonNode json = objectMapper.readTree(
                runtime.dataRoot().resolve("executions/execution-1.json").toFile()
        );
        for (String field : List.of(
                "executionId",
                "goal",
                "origin",
                "status",
                "steps",
                "arguments",
                "outputs",
                "snapshots",
                "events",
                "createdAt",
                "startedAt",
                "completedAt",
                "updatedAt"
        )) {
            assertThat(json.has(field)).as(field).isTrue();
        }
    }

    @Test
    void rollbackPolicyAllowsPartialRetryButRequiresCompletedStep() {
        Instant now = Instant.parse("2026-11-15T02:03:04Z");
        ExecutionRecordStep completedStep = new ExecutionRecordStep(
                "a1",
                "module_todo",
                "todo.create",
                "创建待办",
                RiskLevel.WRITE,
                Map.of(),
                "COMPLETED",
                Map.of(),
                Map.of(),
                "完成"
        );
        ExecutionRecordStep retryableStep = new ExecutionRecordStep(
                "a2",
                "module_todo",
                "todo.create",
                "创建待办",
                RiskLevel.WRITE,
                Map.of(),
                "ROLLBACK_FAILED",
                Map.of(),
                Map.of(),
                "上次回滚失败"
        );
        ExecutionRecord partial = new ExecutionRecord(
                "execution-partial",
                "部分回滚重试",
                "AI_DIRECT",
                "PARTIALLY_ROLLED_BACK",
                List.of(completedStep, retryableStep),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                now,
                now,
                now,
                now
        );
        ExecutionRecord noCompleted = new ExecutionRecord(
                "execution-empty",
                "没有完成步骤",
                "AI_DIRECT",
                "COMPLETED",
                List.of(
                        new ExecutionRecordStep(
                                "a1",
                                "module_todo",
                                "todo.create",
                                "创建待办",
                                RiskLevel.WRITE,
                                Map.of(),
                                "FAILED",
                                Map.of(),
                                Map.of(),
                                "失败"
                        )
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                now,
                now,
                now,
                now
        );

        assertThat(ExecutionRollbackPolicy.assess(partial).canRollback()).isTrue();
        assertThat(ExecutionRollbackPolicy.isRollbackCandidate(retryableStep)).isTrue();
        assertThat(ExecutionRollbackPolicy.assess(noCompleted).canRollback()).isFalse();
        assertThat(ExecutionRollbackPolicy.assess(noCompleted).reason())
                .contains("没有可回滚或可重试的步骤");
    }

    @Test
    void legacyRecordWithoutGoalUsesStepTitleInSummary() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        WorkspaceRuntime runtime = new WorkspaceRuntime(
                "target/execution-record-legacy-" + UUID.randomUUID(),
                objectMapper
        );
        runtime.initialize();
        ExecutionRecordStore store = new ExecutionRecordStore(objectMapper, runtime);
        store.initialize();

        Instant timestamp = Instant.parse("2026-11-15T02:03:04Z");
        ExecutionRecordStep step = new ExecutionRecordStep(
                "a1",
                "module_todo",
                "todo.create",
                "创建待办",
                RiskLevel.WRITE,
                Map.of(),
                "COMPLETED",
                Map.of(),
                Map.of(),
                "完成"
        );
        ExecutionRecord legacy = new ExecutionRecord(
                "execution-legacy",
                "AI_DIRECT",
                "COMPLETED",
                List.of(step),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                timestamp,
                timestamp,
                timestamp,
                timestamp
        );
        ObjectNode legacyJson = objectMapper.valueToTree(legacy);
        legacyJson.remove("goal");
        Files.writeString(
                runtime.dataRoot().resolve("executions/execution-legacy.json"),
                legacyJson.toString(),
                StandardCharsets.UTF_8
        );

        ExecutionRecord loaded = store.require("execution-legacy");
        assertThat(loaded.goal()).isEmpty();
        assertThat(store.list().getFirst().goal()).isEqualTo("创建待办");
    }
}
