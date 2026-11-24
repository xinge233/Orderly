package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRemovalStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndClearsPendingRemovals() throws Exception {
        Path stateFile = temporaryDirectory.resolve("module-removals.json");
        ModuleRemovalStore store = new ModuleRemovalStore(objectMapper, stateFile, false);
        store.initialize();
        ModuleRemovalStore.Entry entry = new ModuleRemovalStore.Entry(
                "module_demo",
                "20261125-010203-12345678-1234-1234-1234-123456789abc",
                "module_demo.jar",
                true,
                Instant.parse("2026-11-25T01:02:03Z").toString()
        );

        store.markPending(entry);

        ModuleRemovalStore reloaded = new ModuleRemovalStore(objectMapper, stateFile, false);
        reloaded.initialize();
        assertThat(reloaded.pendingModuleIds()).containsExactly("module_demo");
        assertThat(reloaded.find("module_demo")).isEqualTo(entry);

        reloaded.clear("module_demo");
        ModuleRemovalStore cleared = new ModuleRemovalStore(objectMapper, stateFile, false);
        cleared.initialize();
        assertThat(cleared.pendingModuleIds()).isEmpty();
    }

    @Test
    void rejectsProtectedOrMalformedEntries() {
        ModuleRemovalStore store = new ModuleRemovalStore(
                objectMapper,
                temporaryDirectory.resolve("module-removals.json"),
                false
        );

        assertThatThrownBy(() -> store.markPending(new ModuleRemovalStore.Entry(
                "module_system_control",
                "20261125-010203-12345678",
                "module_system_control.jar",
                true,
                Instant.now().toString()
        ))).isInstanceOf(ModuleRemovalStore.ModuleRemovalException.class);
    }

    @Test
    void corruptedStateBlocksReadingInsteadOfSilentlyLoadingRemovedModules() throws Exception {
        Path stateFile = temporaryDirectory.resolve("module-removals.json");
        Files.writeString(
                stateFile,
                "{\"version\":1,\"modules\":[{\"moduleId\":\"module_demo\"}]}",
                StandardCharsets.UTF_8
        );
        ModuleRemovalStore store = new ModuleRemovalStore(objectMapper, stateFile, false);

        assertThatThrownBy(store::initialize)
                .isInstanceOf(ModuleRemovalStore.ModuleRemovalException.class)
                .hasMessageContaining("module-removals.json");
    }
}
