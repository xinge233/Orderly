package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** 只打开配置的模块源码目录，不接受客户端路径。 */
@Service
public class ModuleFolderService {

    private final Path moduleSourceDirectory;
    private final String operatingSystem;
    private final FolderLauncher launcher;
    private final ExecutionJournal journal;

    @Autowired
    public ModuleFolderService(
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${os.name:}") String operatingSystem,
            ExecutionJournal journal
    ) {
        this(
                Path.of(moduleSourceDirectory).toAbsolutePath().normalize(),
                operatingSystem,
                ModuleFolderService::launchWithExplorer,
                journal
        );
    }

    ModuleFolderService(
            Path moduleSourceDirectory,
            String operatingSystem,
            FolderLauncher launcher,
            ExecutionJournal journal
    ) {
        this.moduleSourceDirectory = moduleSourceDirectory.toAbsolutePath().normalize();
        this.operatingSystem = operatingSystem;
        this.launcher = launcher;
        this.journal = journal;
    }

    public Map<String, Boolean> open() {
        if (operatingSystem == null
                || !operatingSystem.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            throw new ApiException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "UNSUPPORTED_PLATFORM",
                    "打开模块目录仅支持 Windows"
            );
        }
        if (!Files.isDirectory(moduleSourceDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(moduleSourceDirectory)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_FOLDER_UNAVAILABLE",
                    "配置的模块源码目录不存在或不是普通目录"
            );
        }
        try {
            launcher.open(moduleSourceDirectory);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "MODULE_FOLDER_OPEN_FAILED",
                    "无法打开模块源码目录"
            );
        }
        journal.append(
                "MODULE_CONTROL",
                null,
                null,
                null,
                "open-folder",
                "OPENED",
                "已打开配置的模块源码目录"
        );
        return Map.of("opened", true);
    }

    private static void launchWithExplorer(Path directory) throws IOException {
        new ProcessBuilder("explorer.exe", directory.toString()).start();
    }

    @FunctionalInterface
    interface FolderLauncher {
        void open(Path directory) throws IOException;
    }
}
