package com.orderly.host.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJournalCompatibilityTest {

    @Test
    void loadsLegacyEventsWithoutOrigin() throws Exception {
        Path data = Path.of("target", "journal-compat-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        WorkspaceRuntime runtime = new WorkspaceRuntime(data.toString(), objectMapper);
        runtime.initialize();
        Files.writeString(
                data.resolve("events.jsonl"),
                """
                {"seq":7,"type":"PLAN","planId":"legacy-plan","stepId":null,"moduleId":null,"actionId":null,"status":"COMPLETED","message":"旧事件","timestamp":"2026-11-15T01:02:03Z"}
                """.trim() + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        ExecutionJournal journal = new ExecutionJournal(objectMapper, runtime);
        journal.load();

        assertThat(journal.snapshot()).singleElement().satisfies(event -> {
            assertThat(event.seq()).isEqualTo(7);
            assertThat(event.origin()).isEqualTo("LEGACY");
            assertThat(event.planId()).isEqualTo("legacy-plan");
        });
    }
}
