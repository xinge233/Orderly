package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.event.ExecutionJournal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 后台模块构建生命周期的只读聚合器与受限优先请求入口。
 *
 * <p>构建状态由 scripts/build-module-background.ps1 原子写入目标文件，源码描述只用于
 * 在模块 JAR 尚未加载时展示真实状态。Action、Query 和执行资格仍只来自已校验的 JAR
 * 契约；本服务不执行模块代码，也不能把源码描述提升为可执行契约。</p>
 */
@Service
public class ModuleLifecycleService {

    private static final String CONTROL_SOURCE = "module_control";
    private static final Pattern SOURCE_NAME = Pattern.compile("module_[a-z0-9_]+");
    private static final Set<String> BUILD_STATUSES =
            Set.of("QUEUED", "BUILDING", "READY", "FAILED");
    private static final long MAX_STATUS_BYTES = 512L * 1024L;
    private static final long MAX_UI_MANIFEST_BYTES = 128L * 1024L;

    private final ContractCatalog catalog;
    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final Path moduleSourceDirectory;
    private final Path buildStatusFile;
    private final Path priorityFile;
    private final boolean backgroundEnabled;

    public ModuleLifecycleService(
            ContractCatalog catalog,
            ObjectMapper objectMapper,
            ExecutionJournal journal,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.module-build-status:target/module-build-status.json}") String buildStatusFile,
            @Value("${orderly.module-build-priority:target/module-build-priority.json}") String priorityFile,
            @Value("${orderly.background-modules-enabled:false}") boolean backgroundEnabled
    ) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
        this.buildStatusFile = Path.of(buildStatusFile).toAbsolutePath().normalize();
        this.priorityFile = Path.of(priorityFile).toAbsolutePath().normalize();
        this.backgroundEnabled = backgroundEnabled;
    }

    /**
     * 合并源码、构建状态和已加载目录，返回稳定排序的生命周期快照。
     */
    public Snapshot snapshot() {
        if (!backgroundEnabled) {
            return new Snapshot(1, "eager", Instant.now().toString(), true, List.of());
        }

        ParsedBuildStatus buildStatus = readBuildStatus();
        String prioritySource = readPrioritySource();
        List<Entry> entries = new ArrayList<>();
        for (Path source : sourceDirectories()) {
            String sourceName = source.getFileName().toString();
            SourceMetadata metadata = readSourceMetadata(source, sourceName);
            BuildRecord build = buildStatus.modules().get(sourceName);
            boolean loaded = isLoaded(sourceName);
            String status = loaded
                    ? "READY"
                    : build == null ? "QUEUED" : build.status();
            String message = loaded
                    ? "模块已由宿主校验并加载"
                    : build == null ? "等待后台构建" : build.message();
            if (!loaded && "READY".equals(status)) {
                // 旧构建状态不能替代当前目录中的已验证 JAR；否则新进程会误判模块已加载。
                status = "QUEUED";
                message = "构建产物等待宿主校验并加载";
            }
            int priority = sourceName.equals(prioritySource)
                    ? Math.max(1, build == null ? 0 : build.priority())
                    : build == null ? 0 : build.priority();
            entries.add(new Entry(
                    sourceName,
                    sourceName,
                    preferred(metadata.title(), build == null ? null : build.title(), sourceName),
                    preferred(metadata.caption(), build == null ? null : build.caption(), sourceName),
                    preferred(metadata.icon(), build == null ? null : build.icon(), "modules"),
                    status,
                    message,
                    priority,
                    build == null ? null : build.startedAt(),
                    build == null ? null : build.finishedAt()
            ));
        }
        entries.sort(Comparator
                .comparingInt((Entry entry) -> entry.priority() > 0 ? 0 : 1)
                .thenComparing(Entry::sourceName));
        return new Snapshot(
                1,
                "background",
                buildStatus.updatedAt() == null ? Instant.now().toString() : buildStatus.updatedAt(),
                entries.stream().allMatch(entry -> "READY".equals(entry.status())),
                List.copyOf(entries)
        );
    }

    /**
     * 为已发现的业务模块源码写入一次优先请求。
     *
     * <p>请求只影响后台脚本后续出队顺序，不会抢占已经运行的 Maven 进程。源码名必须来自
     * 当前源码根的直接子目录，不能接受路径、绝对名称或其他模块目录。</p>
     */
    public Snapshot prioritize(String sourceName) {
        if (!backgroundEnabled) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "BACKGROUND_MODULES_DISABLED",
                    "当前启动未启用后台模块构建"
            );
        }
        Path source = requireSource(sourceName);
        writeJsonAtomically(priorityFile, Map.of(
                "sourceName", source.getFileName().toString()
        ));
        journal.append(
                "MODULE_CONTROL",
                "MODULE_CONTROL",
                null,
                null,
                null,
                "module-build-priority",
                "QUEUED",
                "已请求优先构建模块：" + source.getFileName()
        );
        return snapshot();
    }

    /**
     * AI 回合的宿主侧就绪门禁。
     *
     * <p>后台模块模式下，任一业务模块仍在排队、构建或失败时拒绝创建新回合，避免
     * Python 只看到部分工具卡后生成不完整 Action 数组。前端可以先等待构建完成，再
     * 重新提交原始目标。</p>
     */
    public void requireReadyForAi() {
        if (!backgroundEnabled) {
            return;
        }
        Snapshot current = snapshot();
        if (current.modules().stream().anyMatch(entry -> !"READY".equals(entry.status()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULES_NOT_READY",
                    "仍有业务模块尚未完成构建，AI 回合已暂缓"
            );
        }
    }

    private boolean isLoaded(String sourceName) {
        return catalog.findModule(sourceName)
                .map(module -> catalog.moduleArtifact(module.moduleId())
                        .map(artifact -> sourceName(artifact.fileName()).equals(sourceName))
                        .orElse(false))
                .orElse(false);
    }

    private Path requireSource(String sourceName) {
        if (sourceName == null || !SOURCE_NAME.matcher(sourceName).matches()
                || CONTROL_SOURCE.equals(sourceName)) {
            throw invalidSource(sourceName);
        }
        Path candidate = moduleSourceDirectory.resolve(sourceName).normalize();
        if (!candidate.startsWith(moduleSourceDirectory)
                || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            throw invalidSource(sourceName);
        }
        return candidate;
    }

    private ApiException invalidSource(String sourceName) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_MODULE_SOURCE",
                "模块源码名非法或未发现：" + String.valueOf(sourceName)
        );
    }

    private List<Path> sourceDirectories() {
        if (!Files.isDirectory(moduleSourceDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(moduleSourceDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(moduleSourceDirectory)) {
            return stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> SOURCE_NAME.matcher(path.getFileName().toString()).matches())
                    .filter(path -> !CONTROL_SOURCE.equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描模块源码生命周期失败：" + moduleSourceDirectory, exception);
        }
    }

    private ParsedBuildStatus readBuildStatus() {
        if (!Files.isRegularFile(buildStatusFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(buildStatusFile)) {
            return ParsedBuildStatus.empty();
        }
        try {
            long size = Files.size(buildStatusFile);
            if (size <= 0 || size > MAX_STATUS_BYTES) {
                return ParsedBuildStatus.empty();
            }
            JsonNode root = objectMapper.readTree(Files.readString(buildStatusFile, StandardCharsets.UTF_8));
            if (root == null || root.path("schemaVersion").asInt(-1) != 1
                    || !"background".equals(root.path("mode").asText())
                    || !root.path("modules").isArray()) {
                return ParsedBuildStatus.empty();
            }
            Map<String, BuildRecord> modules = new LinkedHashMap<>();
            for (JsonNode node : root.path("modules")) {
                String sourceName = node.path("sourceName").asText("");
                String status = node.path("status").asText("");
                if (!SOURCE_NAME.matcher(sourceName).matches()
                        || CONTROL_SOURCE.equals(sourceName)
                        || !BUILD_STATUSES.contains(status)) {
                    continue;
                }
                modules.put(sourceName, new BuildRecord(
                        status,
                        optionalText(node, "message"),
                        optionalText(node, "title"),
                        optionalText(node, "caption"),
                        optionalText(node, "icon"),
                        Math.max(0, node.path("priority").asInt(0)),
                        optionalText(node, "startedAt"),
                        optionalText(node, "finishedAt")
                ));
            }
            return new ParsedBuildStatus(
                    root.path("updatedAt").asText(Instant.now().toString()),
                    root.path("complete").asBoolean(false),
                    Map.copyOf(modules)
            );
        } catch (IOException | RuntimeException exception) {
            return ParsedBuildStatus.empty();
        }
    }

    private String readPrioritySource() {
        if (!Files.isRegularFile(priorityFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(priorityFile)) {
            return null;
        }
        try {
            if (Files.size(priorityFile) <= 0 || Files.size(priorityFile) > MAX_STATUS_BYTES) {
                return null;
            }
            String sourceName = objectMapper.readTree(
                    Files.readString(priorityFile, StandardCharsets.UTF_8)
            ).path("sourceName").asText("");
            return SOURCE_NAME.matcher(sourceName).matches()
                    && !CONTROL_SOURCE.equals(sourceName)
                    ? sourceName
                    : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private SourceMetadata readSourceMetadata(Path source, String sourceName) {
        Path manifest = source.resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("orderly-ui")
                .resolve("module.json")
                .normalize();
        if (!manifest.startsWith(source)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifest)) {
            return SourceMetadata.fallback(sourceName);
        }
        try {
            if (Files.size(manifest) <= 0 || Files.size(manifest) > MAX_UI_MANIFEST_BYTES) {
                return SourceMetadata.fallback(sourceName);
            }
            JsonNode root = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            JsonNode view = root.path("view");
            JsonNode card = root.path("card");
            String caption = safeText(view.path("caption").asText(""), sourceName);
            String icon = safeText(view.path("icon").asText(""), "modules");
            String title = safeText(card.path("title").asText(""), caption);
            return new SourceMetadata(title, caption, icon);
        } catch (IOException | RuntimeException exception) {
            return SourceMetadata.fallback(sourceName);
        }
    }

    private void writeJsonAtomically(Path target, Object value) {
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
            Path temp = parent.resolve("." + target.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            Files.write(temp, content);
            try {
                Files.move(
                        temp,
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入模块构建优先请求失败：" + target, exception);
        }
    }

    private String sourceName(String jarFileName) {
        return jarFileName.endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - 4)
                : jarFileName;
    }

    private String preferred(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    public record Snapshot(
            int schemaVersion,
            String mode,
            String updatedAt,
            boolean complete,
            List<Entry> modules
    ) {
    }

    public record Entry(
            String sourceName,
            String moduleId,
            String title,
            String caption,
            String icon,
            String status,
            String message,
            int priority,
            String startedAt,
            String finishedAt
    ) {
    }

    private record BuildRecord(
            String status,
            String message,
            String title,
            String caption,
            String icon,
            int priority,
            String startedAt,
            String finishedAt
    ) {
    }

    private record ParsedBuildStatus(
            String updatedAt,
            boolean complete,
            Map<String, BuildRecord> modules
    ) {
        private static ParsedBuildStatus empty() {
            return new ParsedBuildStatus(null, false, Map.of());
        }
    }

    private record SourceMetadata(String title, String caption, String icon) {
        private static SourceMetadata fallback(String sourceName) {
            return new SourceMetadata(sourceName, sourceName, "modules");
        }
    }
}
