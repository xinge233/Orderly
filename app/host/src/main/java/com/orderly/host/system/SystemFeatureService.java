package com.orderly.host.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** 宿主级功能开关。当前只包含本地规则计划生成能力。 */
@Service
public class SystemFeatureService {

    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final Path settingsFile;
    private volatile FeatureSettings settings = new FeatureSettings(false);

    public SystemFeatureService(
            ObjectMapper objectMapper,
            WorkspaceRuntime runtime,
            ExecutionJournal journal
    ) {
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.settingsFile = runtime.dataRoot().resolve("system-features.json");
    }

    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(settingsFile.getParent());
            if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                settings = new FeatureSettings(false);
                persist(settings);
                return;
            }
            if (Files.isSymbolicLink(settingsFile) || !Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("系统功能设置必须是普通本地文件：" + settingsFile);
            }
            FeatureSettings loaded = objectMapper.readValue(settingsFile.toFile(), FeatureSettings.class);
            settings = new FeatureSettings(loaded.planningEnabled());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取系统功能设置失败：" + settingsFile + "；原因：" + exception.getMessage(),
                    exception
            );
        }
    }

    public boolean planningEnabled() {
        return settings.planningEnabled();
    }

    public Map<String, Object> features() {
        return Map.of("planningEnabled", planningEnabled());
    }

    public synchronized Map<String, Object> setPlanningEnabled(boolean enabled) {
        FeatureSettings next = new FeatureSettings(enabled);
        persist(next);
        settings = next;
        journal.append(
                "SYSTEM_FEATURE",
                "SYSTEM",
                null,
                null,
                null,
                "planning",
                enabled ? "ENABLED" : "DISABLED",
                enabled ? "计划生成功能已启用" : "计划生成功能已停用"
        );
        return features();
    }

    public void requirePlanningEnabled() {
        if (!planningEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "FEATURE_DISABLED",
                    "计划生成功能已关闭；历史执行记录仍可读取"
            );
        }
    }

    private void persist(FeatureSettings value) {
        Path temporary = settingsFile.resolveSibling("system-features.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(
                        temporary,
                        settingsFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入系统功能设置失败：" + settingsFile, exception);
        }
    }

    public record FeatureSettings(boolean planningEnabled) {
    }
}
