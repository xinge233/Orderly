package com.orderly.host.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.modulecontrol.ModuleRemovalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCatalogRemovalTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsJarsRegisteredForRemovalBeforeContractScanning() throws Exception {
        Path modules = temporaryDirectory.resolve("modules");
        Path data = temporaryDirectory.resolve("data");
        Files.createDirectories(modules);
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(modules.resolve("module_demo.jar"))
        )) {
            // 空 JAR 只有在扫描排除生效时才不会被当成无效模块契约。
        }

        ModuleRemovalStore store = new ModuleRemovalStore(objectMapper, data.toString());
        store.initialize();
        store.markPending(new ModuleRemovalStore.Entry(
                "module_demo",
                "20261125-013000-12345678-1234-1234-1234-123456789abc",
                "module_demo.jar",
                true,
                Instant.now().toString()
        ));

        try (ContractCatalog catalog = new ContractCatalog(
                objectMapper,
                modules.toString(),
                store
        )) {
            assertThat(catalog.modules()).isEmpty();
        }
    }
}
