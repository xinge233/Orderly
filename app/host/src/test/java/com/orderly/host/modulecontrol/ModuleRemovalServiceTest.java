package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSystemMetadata;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.api.ApiException;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleRemovalServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void movesSourceToTrashAndRestoresItBeforeRestart() throws Exception {
        Fixture fixture = fixture();

        ModuleRemovalService.Change removed = fixture.service.remove("module_demo");

        assertThat(removed.status()).isEqualTo("PENDING_RESTART");
        assertThat(fixture.source).doesNotExist();
        assertThat(fixture.store.find("module_demo")).isNotNull();
        assertThat(fixture.trash.trashRoot()
                .resolve(fixture.store.find("module_demo").trashId())
                .resolve("module_demo"))
                .isDirectory();
        verify(fixture.preferences).setEnabled("module_demo", false);

        ModuleRemovalService.Change restored = fixture.service.undo("module_demo");

        assertThat(restored.status()).isEqualTo("RESTORED");
        assertThat(fixture.source.resolve("README.txt")).exists();
        assertThat(fixture.store.find("module_demo")).isNull();
        verify(fixture.preferences).setEnabled("module_demo", true);
    }

    @Test
    void finalizesPendingJarCleanupAtStartup() throws Exception {
        Fixture fixture = fixture();
        String trashId = fixture.trash.newTrashId();
        fixture.store.markPending(new ModuleRemovalStore.Entry(
                "module_demo",
                trashId,
                "module_demo.jar",
                true,
                Instant.now().toString()
        ));

        fixture.service.finalizePendingRemovals();

        assertThat(fixture.source).doesNotExist();
        assertThat(fixture.jar).doesNotExist();
        assertThat(fixture.trash.trashRoot().resolve(trashId).resolve("module_demo.jar"))
                .exists();
        assertThat(fixture.store.find("module_demo")).isNull();
    }

    @Test
    void rejectsSystemModulesAndExecutionsInProgress() throws Exception {
        Fixture fixture = fixture();
        when(fixture.module.system()).thenReturn(mock(ModuleSystemMetadata.class));
        assertThatThrownBy(() -> fixture.service.remove("module_demo"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("系统模块");

        when(fixture.module.system()).thenReturn(null);
        try (var permit = fixture.coordinator.beginExecution()) {
            assertThatThrownBy(() -> fixture.service.remove("module_demo"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("执行");
        }
    }

    private Fixture fixture() throws Exception {
        Path dataRoot = temporaryDirectory.resolve(".orderly-data");
        Path sourceRoot = temporaryDirectory.resolve("Module");
        Path modulesRoot = temporaryDirectory.resolve("target/orderly-modules");
        Path source = sourceRoot.resolve("module_demo");
        Path jar = modulesRoot.resolve("module_demo.jar");
        Files.createDirectories(source);
        Files.createDirectories(modulesRoot);
        Files.writeString(source.resolve("README.txt"), "demo", StandardCharsets.UTF_8);
        Files.writeString(jar, "jar", StandardCharsets.UTF_8);

        WorkspaceRuntime runtime = new WorkspaceRuntime(dataRoot.toString(), objectMapper);
        runtime.initialize();
        ModuleTrashService trash = new ModuleTrashService(
                runtime,
                sourceRoot.toString(),
                modulesRoot.toString(),
                objectMapper
        );
        trash.initialize();
        ModuleRemovalStore store = new ModuleRemovalStore(
                objectMapper,
                dataRoot.resolve("module-removals.json"),
                false
        );
        store.initialize();

        ContractCatalog catalog = mock(ContractCatalog.class);
        ModuleContract module = mock(ModuleContract.class);
        when(module.moduleId()).thenReturn("module_demo");
        when(module.system()).thenReturn(null);
        when(catalog.findModule("module_demo")).thenReturn(Optional.of(module));
        when(catalog.businessModules()).thenReturn(List.of(module));

        ModulePreferencesService preferences = mock(ModulePreferencesService.class);
        when(preferences.isEnabled("module_demo")).thenReturn(true);
        when(preferences.revision()).thenReturn("revision-1");

        ModuleLifecycleService lifecycle = mock(ModuleLifecycleService.class);
        when(lifecycle.snapshot()).thenReturn(new ModuleLifecycleService.Snapshot(
                1,
                "background",
                Instant.now().toString(),
                true,
                List.of()
        ));
        ExecutionJournal journal = mock(ExecutionJournal.class);
        ModuleControlCoordinator coordinator = new ModuleControlCoordinator();
        ModuleRemovalService service = new ModuleRemovalService(
                catalog,
                preferences,
                coordinator,
                store,
                trash,
                lifecycle,
                journal,
                sourceRoot.toString()
        );
        return new Fixture(
                service,
                store,
                trash,
                module,
                coordinator,
                preferences,
                source,
                jar
        );
    }

    private record Fixture(
            ModuleRemovalService service,
            ModuleRemovalStore store,
            ModuleTrashService trash,
            ModuleContract module,
            ModuleControlCoordinator coordinator,
            ModulePreferencesService preferences,
            Path source,
            Path jar
    ) {
    }
}
