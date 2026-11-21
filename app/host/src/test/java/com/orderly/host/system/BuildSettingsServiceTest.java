package com.orderly.host.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BuildSettingsServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsToTwoAndPersistsSupportedValues() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspaceRuntime runtime = new WorkspaceRuntime(temporaryDirectory.toString(), objectMapper);
        ExecutionJournal journal = mock(ExecutionJournal.class);
        BuildSettingsService service = new BuildSettingsService(objectMapper, runtime, journal);

        service.initialize();

        assertThat(service.snapshot())
                .containsEntry("maxParallel", 2)
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 4);
        assertThat(runtime.dataRoot().resolve("build-settings.json")).exists();

        assertThat(service.setMaxParallel(4)).containsEntry("maxParallel", 4);

        BuildSettingsService reloaded = new BuildSettingsService(objectMapper, runtime, journal);
        reloaded.initialize();
        assertThat(reloaded.snapshot()).containsEntry("maxParallel", 4);
    }

    @Test
    void rejectsValuesOutsideSupportedRange() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspaceRuntime runtime = new WorkspaceRuntime(temporaryDirectory.toString(), objectMapper);
        BuildSettingsService service = new BuildSettingsService(
                objectMapper,
                runtime,
                mock(ExecutionJournal.class)
        );
        service.initialize();

        assertThatThrownBy(() -> service.setMaxParallel(0))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INVALID_BUILD_CONCURRENCY");
        assertThatThrownBy(() -> service.setMaxParallel(5))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INVALID_BUILD_CONCURRENCY");
    }

    @Test
    void rejectsCorruptSettingsInsteadOfSilentlyChangingConcurrency() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspaceRuntime runtime = new WorkspaceRuntime(temporaryDirectory.toString(), objectMapper);
        Files.createDirectories(runtime.dataRoot());
        Files.writeString(runtime.dataRoot().resolve("build-settings.json"), """
                {"schemaVersion":1,"maxParallel":5}
                """);
        BuildSettingsService service = new BuildSettingsService(
                objectMapper,
                runtime,
                mock(ExecutionJournal.class)
        );

        assertThatThrownBy(service::initialize)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INVALID_BUILD_CONCURRENCY");
    }
}
