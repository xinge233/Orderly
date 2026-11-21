package com.orderly.host.system;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;

/**
 * 后台模块构建并发设置。
 *
 * <p>设置保存在应用自管数据目录的 {@code build-settings.json}。并发数只控制 Maven
 * worker 数量，不改变单线程校验、发布和宿主刷新边界。</p>
 */
@Service
public class BuildSettingsService {

    public static final int DEFAULT_MAX_PARALLEL = 2;
    public static final int MIN_MAX_PARALLEL = 1;
    public static final int MAX_MAX_PARALLEL = 4;
    static final int SCHEMA_VERSION = 1;

    private static final Set<String> FIELDS = Set.of("schemaVersion", "maxParallel");

    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final Path settingsFile;
    private volatile Settings settings = new Settings(SCHEMA_VERSION, DEFAULT_MAX_PARALLEL);

    public BuildSettingsService(
            ObjectMapper objectMapper,
            WorkspaceRuntime runtime,
            ExecutionJournal journal
    ) {
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.settingsFile = runtime.dataRoot().resolve("build-settings.json");
    }

    /** 加载设置；缺失时创建默认值，损坏或越界时阻止启动。 */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(settingsFile.getParent());
            if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                settings = new Settings(SCHEMA_VERSION, DEFAULT_MAX_PARALLEL);
                persist(settings);
                return;
            }
            if (Files.isSymbolicLink(settingsFile) || !Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("模块构建设置必须是普通本地文件：" + settingsFile);
            }
            JsonNode root = objectMapper.readTree(settingsFile.toFile());
            requireExactFields(root);
            if (root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                throw new IllegalStateException("模块构建设置 schemaVersion 必须为 1：" + settingsFile);
            }
            int maxParallel = root.path("maxParallel").asInt(-1);
            requireSupported(maxParallel);
            settings = new Settings(SCHEMA_VERSION, maxParallel);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取模块构建设置失败：" + settingsFile + "；原因：" + exception.getMessage(),
                    exception
            );
        }
    }

    /** 返回设置页与后台构建器共享的稳定快照。 */
    public Map<String, Object> snapshot() {
        return Map.of(
                "maxParallel", settings.maxParallel(),
                "minimum", MIN_MAX_PARALLEL,
                "maximum", MAX_MAX_PARALLEL
        );
    }

    /** 持久化并发数并记录审计事件；失败时不发布内存新值。 */
    public synchronized Map<String, Object> setMaxParallel(int maxParallel) {
        requireSupported(maxParallel);
        Settings next = new Settings(SCHEMA_VERSION, maxParallel);
        persist(next);
        settings = next;
        journal.append(
                "SYSTEM_SETTINGS",
                "SYSTEM",
                null,
                null,
                null,
                "build-concurrency",
                "UPDATED",
                "模块构建并发数已调整为 " + maxParallel
        );
        return snapshot();
    }

    private void requireExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("模块构建设置必须是 JSON 对象：" + settingsFile);
        }
        Set<String> actual = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(FIELDS)) {
            throw new IllegalStateException("模块构建设置字段非法：" + settingsFile);
        }
    }

    private void requireSupported(int maxParallel) {
        if (maxParallel < MIN_MAX_PARALLEL || maxParallel > MAX_MAX_PARALLEL) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BUILD_CONCURRENCY",
                    "模块构建并发数必须在 " + MIN_MAX_PARALLEL + "-" + MAX_MAX_PARALLEL + " 之间"
            );
        }
    }

    private void persist(Settings value) {
        Path temporary = settingsFile.resolveSibling("build-settings.json.tmp");
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
            throw new IllegalStateException("写入模块构建设置失败：" + settingsFile, exception);
        }
    }

    public record Settings(int schemaVersion, int maxParallel) {
    }
}
