package com.orderly.host;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 模块初始化只能在首次启动播种，不能覆盖用户主动清空后的状态。 */
@SpringBootTest
class ModuleInitializationIntegrationTest {

    private static final String TEST_DATA = "target/module-init-data-" + UUID.randomUUID();

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("orderly.data-dir", () -> TEST_DATA);
    }

    @Autowired
    private ContractCatalog catalog;

    @Autowired
    private WorkspaceRuntime runtime;

    @Test
    void doesNotReseedCollectionsThatWereExplicitlyCleared() {
        runtime.writeItems("todos", List.of());
        runtime.writeItems("calendar", List.of());

        catalog.initializeModules(runtime);

        assertThat(runtime.readItems("todos")).isEmpty();
        assertThat(runtime.readItems("calendar")).isEmpty();
    }

    @Test
    void doesNotRecreateSeededFilesAfterTheyEnterTrash() {
        Path note = runtime.resolveWorkspacePath("收件箱/示例笔记.txt");
        assertThat(note).exists();

        runtime.moveToTrash(note, "module-init-test", "seed-file");
        catalog.initializeModules(runtime);

        assertThat(note).doesNotExist();
    }
}
