package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModuleFolderServiceTest {

    @Test
    void windowsOpensOnlyConfiguredSourceDirectory() throws Exception {
        Path source = testDirectory("windows");
        AtomicReference<Path> opened = new AtomicReference<>();
        ExecutionJournal journal = mock(ExecutionJournal.class);
        ModuleFolderService service = new ModuleFolderService(
                source,
                "Windows 11",
                opened::set,
                journal
        );

        assertThat(service.open()).containsEntry("opened", true);
        assertThat(opened.get()).isEqualTo(source.toAbsolutePath().normalize());
        verify(journal).append(
                eq("MODULE_CONTROL"),
                isNull(),
                isNull(),
                isNull(),
                eq("open-folder"),
                eq("OPENED"),
                anyString()
        );
    }

    @Test
    void nonWindowsPlatformRejectsFolderOpen() throws Exception {
        Path source = testDirectory("linux");
        AtomicReference<Path> opened = new AtomicReference<>();
        ModuleFolderService service = new ModuleFolderService(
                source,
                "Linux",
                opened::set,
                mock(ExecutionJournal.class)
        );

        assertThatThrownBy(service::open)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅支持 Windows");
        assertThat(opened).hasValue(null);
    }

    private Path testDirectory(String suffix) throws Exception {
        Path directory = Path.of("target", "module-folder-" + suffix + "-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(directory);
        return directory;
    }
}
