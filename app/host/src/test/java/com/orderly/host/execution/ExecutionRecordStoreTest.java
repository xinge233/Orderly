package com.orderly.host.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.Test;

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
        assertThat(loaded.origin()).isEqualTo("AI_DIRECT");
        assertThat(loaded.steps()).hasSize(1);
        assertThat(loaded.snapshots()).containsKey("a1");
        assertThat(loaded.outputs()).containsKey("a1");
        assertThat(loaded.events()).singleElement()
                .extracting(ExecutionEvent::origin)
                .isEqualTo("AI_DIRECT");

        JsonNode json = objectMapper.readTree(
                runtime.dataRoot().resolve("executions/execution-1.json").toFile()
        );
        for (String field : List.of(
                "executionId",
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
}
