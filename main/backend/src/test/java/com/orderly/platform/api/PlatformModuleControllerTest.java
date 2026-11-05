package com.orderly.platform.api;

import com.orderly.platform.module.ModuleRegistry;
import com.orderly.platform.module.SampleTestModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证模块目录接口在最小上下文中可被发现。
 */
@WebMvcTest(PlatformModuleController.class)
@Import({ModuleRegistry.class, SampleTestModule.class, ModuleCatalogMapper.class})
class PlatformModuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnModuleCatalog() throws Exception {
        mockMvc.perform(get("/api/platform/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("1.0"))
                .andExpect(jsonPath("$.mode").value("modular-skeleton"))
                .andExpect(jsonPath("$.modules[0].id").value("sample-empty"))
                .andExpect(jsonPath("$.modules[0].actions[0].id").value("ping"))
                .andExpect(jsonPath("$.modules[0].capabilities").doesNotExist());
    }
}
