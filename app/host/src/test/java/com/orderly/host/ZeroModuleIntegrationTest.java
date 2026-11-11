package com.orderly.host;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 零模块是合法部署状态；计划生成必须安全转为澄清而不是 500。 */
@SpringBootTest
@AutoConfigureMockMvc
class ZeroModuleIntegrationTest {

    private static final String TEST_DATA = "target/zero-module-data-" + UUID.randomUUID();
    private static final Path EMPTY_MODULES = Path.of(
            "target", "zero-modules-" + UUID.randomUUID()).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void emptyModuleDirectory(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> TEST_DATA);
        registry.add("orderly.modules-dir", () -> EMPTY_MODULES.toString());
    }

    @Autowired
    private ContractCatalog catalog;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRuntime runtime;

    @Test
    void startsWithZeroModulesAndReturnsEmptyArray() throws Exception {
        assertThat(catalog.modules()).isEmpty();
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(runtime.listDirectory(runtime.workspaceRoot())).isEmpty();
        mockMvc.perform(get("/api/modules/module_fs/query/fs.list"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_FOUND"));
        mockMvc.perform(get("/api/modules/module_fs/ui/module.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_UI_NOT_FOUND"));
    }

    @Test
    void unavailableModuleActionBecomesQuestionWithoutExecution() throws Exception {
        mockMvc.perform(post("/api/plans")
                        .contentType("application/json")
                        .content("{\"goal\":\"创建目录 零模块安全目录\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.steps.length()").value(0));
    }
}
