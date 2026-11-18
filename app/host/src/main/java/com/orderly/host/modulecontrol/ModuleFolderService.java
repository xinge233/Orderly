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

/**
 * 在 Windows 上打开宿主配置的模块源码目录。
 *
 * <p>客户端不能提供路径。目录在构造时规范化为绝对路径，打开前拒绝符号链接和非普通
 * 目录；只有启动成功后才会追加 MODULE_CONTROL 审计事件。</p>
 */
@Service
public class ModuleFolderService {

    private final Path moduleSourceDirectory;
    private final String operatingSystem;
    private final FolderLauncher launcher;
    private final ExecutionJournal journal;

    /** 从宿主配置读取模块源码目录并绑定系统目录启动器。 */
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

    /** 供定向测试注入目录、平台与启动器的构造入口，不接收客户端路径。 */
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

    /**
     * 校验平台与配置目录后调用系统目录启动器。
     *
     * <p>非 Windows 返回 501，路径缺失、不是普通目录或为符号链接返回冲突；启动失败不
     * 写成功审计。</p>
     */
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

    /** 使用固定的 explorer.exe 打开已校验的目录，不拼接客户端参数。 */
    private static void launchWithExplorer(Path directory) throws IOException {
        new ProcessBuilder("explorer.exe", directory.toString()).start();
    }

    /** 可替换的系统目录启动边界，便于测试而不访问真实桌面。 */
    @FunctionalInterface
    interface FolderLauncher {
        void open(Path directory) throws IOException;
    }
}
