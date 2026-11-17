package com.orderly.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.system.SystemFeatureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SystemFeatureIntegrationTest {

    private static final String TEST_DATA = "target/system-feature-data-" + UUID.randomUUID();

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> TEST_DATA);
        registry.add("orderly.themes-dir", () -> System.getProperty("orderly.themes-dir"));
    }

    @Autowired
    private SystemFeatureService features;

    @Autowired
    private WorkspaceRuntime runtime;

    @Autowired
    private ExecutionJournal journal;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void planningDefaultsOffAndPersistsAcrossServiceReload() throws Exception {
        mockMvc.perform(get("/api/system/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planningEnabled").value(false))
                .andExpect(jsonPath("$.aiPlanEnabled").value(true));
        mockMvc.perform(post("/api/plans")
                        .contentType("application/json")
                        .content("{\"goal\":\"创建目录 默认关闭\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));

        mockMvc.perform(put("/api/system/features/ai-plan")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiPlanEnabled").value(false));
        mockMvc.perform(put("/api/system/features/ai-plan")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        features.setPlanningEnabled(true);
        SystemFeatureService reloaded = new SystemFeatureService(objectMapper, runtime, journal);
        reloaded.initialize();
        assertThat(reloaded.planningEnabled()).isTrue();
        assertThat(reloaded.aiPlansEnabled()).isFalse();

        Files.writeString(
                runtime.dataRoot().resolve("system-features.json"),
                "{\"planningEnabled\":true}",
                StandardCharsets.UTF_8
        );
        SystemFeatureService legacyReloaded = new SystemFeatureService(
                objectMapper,
                runtime,
                journal
        );
        legacyReloaded.initialize();
        assertThat(legacyReloaded.planningEnabled()).isTrue();
        assertThat(legacyReloaded.aiPlansEnabled()).isTrue();
        assertThat(journal.snapshot()).anyMatch(event ->
                "SYSTEM_FEATURE".equals(event.type())
                        && "SYSTEM".equals(event.origin())
                        && "ai-plan".equals(event.actionId()));
    }
}
