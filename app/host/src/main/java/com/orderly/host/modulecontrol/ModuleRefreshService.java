package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleEntry;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 显式刷新只在源码与已构建 JAR 同时出现时追加新业务模块。
 *
 * <p>已有模块的源码或 JAR 一旦变化，刷新只报告重启需求，不替换当前运行快照。</p>
 */
@Service
public class ModuleRefreshService {

    private final ContractCatalog catalog;
    private final ModulePreferencesService preferences;
    private final ModuleControlCoordinator coordinator;
    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final WorkspaceRuntime runtime;
    private final Path moduleSourceDirectory;
    private final Path modulesDirectory;

    private Map<String, String> baselineSourceFingerprints = Map.of();
    private Map<String, ArtifactFingerprint> baselineArtifacts = Map.of();

    public ModuleRefreshService(
            ContractCatalog catalog,
            ModulePreferencesService preferences,
            ModuleControlCoordinator coordinator,
            ObjectMapper objectMapper,
            ExecutionJournal journal,
            WorkspaceRuntime runtime,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.modules-dir:target/orderly-modules}") String modulesDirectory
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.coordinator = coordinator;
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.runtime = runtime;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
        this.modulesDirectory = Path.of(modulesDirectory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void captureBaseline() {
        baselineSourceFingerprints = scanSourceDirectories().stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        this::sourceFingerprint,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, ArtifactFingerprint> fingerprints = new LinkedHashMap<>();
        for (ModuleContract module : catalog.modules()) {
            catalog.moduleArtifact(module.moduleId()).ifPresent(artifact ->
                    fingerprints.put(
                            module.moduleId(),
                            new ArtifactFingerprint(
                                    sourceName(artifact.fileName()),
                                    artifact.size(),
                                    artifact.lastModifiedMillis(),
                                    fileDigest(artifact.path())
                            )
                    )
            );
        }
        baselineArtifacts = Map.copyOf(fingerprints);
    }

    public ModuleRefreshResult refresh() {
        return coordinator.mutateRejectingDuringExecution(this::doRefresh);
    }

    private ModuleRefreshResult doRefresh() {
        Set<String> currentSources = new LinkedHashSet<>(scanSourceDirectories());
        List<ModuleRefreshIssue> issues = new ArrayList<>();
        Set<String> changedExistingModules = new LinkedHashSet<>();

        for (ModuleContract module : catalog.modules()) {
            ArtifactFingerprint baseline = baselineArtifacts.get(module.moduleId());
            if (baseline == null) {
                issues.add(new ModuleRefreshIssue(
                        "RESTART_REQUIRED",
                        "模块 " + module.moduleId() + " 缺少启动快照，刷新不会替换当前实例"
                ));
                continue;
            }
            if (!currentSources.contains(baseline.sourceName())) {
                changedExistingModules.add(module.moduleId());
                issues.add(new ModuleRefreshIssue(
                        "RESTART_REQUIRED",
                        "模块 " + module.moduleId() + " 的源码目录已删除，必须重启宿主"
                ));
                continue;
            }
            String currentFingerprint = sourceFingerprint(baseline.sourceName());
            if (!baselineSourceFingerprints.getOrDefault(baseline.sourceName(), "").equals(currentFingerprint)) {
                changedExistingModules.add(module.moduleId());
                issues.add(new ModuleRefreshIssue(
                        "RESTART_REQUIRED",
                        "模块 " + module.moduleId() + " 的源码已更新，必须重启宿主"
                ));
            }
            Path jar = modulesDirectory.resolve(baseline.sourceName() + ".jar").normalize();
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(jar)
                    || !artifactFingerprint(jar).equals(baseline)) {
                changedExistingModules.add(module.moduleId());
                issues.add(new ModuleRefreshIssue(
                        "RESTART_REQUIRED",
                        "模块 " + module.moduleId() + " 的 JAR 已变化，必须重启宿主"
                ));
            }
        }

        List<ModuleEntry> added = new ArrayList<>();
        Map<String, String> updatedSourceFingerprints = new LinkedHashMap<>(baselineSourceFingerprints);
        Map<String, ArtifactFingerprint> updatedArtifacts = new LinkedHashMap<>(baselineArtifacts);
        for (String sourceName : currentSources.stream().sorted().toList()) {
            if (baselineSourceFingerprints.containsKey(sourceName)
                    || changedExistingModules.stream().anyMatch(moduleId ->
                    sourceName.equals(baselineArtifacts.get(moduleId).sourceName()))) {
                continue;
            }
            Path jar = modulesDirectory.resolve(sourceName + ".jar").normalize();
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) {
                issues.add(new ModuleRefreshIssue(
                        "MODULE_NOT_BUILT",
                        "新增模块 " + sourceName + " 尚未构建到 " + jar
                ));
                continue;
            }
            try {
                String nextSourceFingerprint = sourceFingerprint(sourceName);
                ArtifactFingerprint nextArtifact = artifactFingerprint(jar);
                ModuleContract module;
                try (ContractCatalog.PreparedModule prepared = catalog.prepareModule(
                        jar,
                        runtime,
                        objectMapper
                )) {
                    prepared.commit();
                    try {
                        preferences.registerBusinessModule(prepared.module());
                    } catch (RuntimeException exception) {
                        prepared.rollback();
                        throw exception;
                    }
                    module = prepared.module();
                }
                added.add(ModuleEntry.from(module, true));
                updatedSourceFingerprints.put(sourceName, nextSourceFingerprint);
                updatedArtifacts.put(module.moduleId(), nextArtifact);
            } catch (RuntimeException exception) {
                issues.add(new ModuleRefreshIssue(
                        "MODULE_LOAD_FAILED",
                        "新增模块 " + sourceName + " 加载失败：" + safeMessage(exception)
                ));
            }
        }
        baselineSourceFingerprints = Map.copyOf(updatedSourceFingerprints);
        baselineArtifacts = Map.copyOf(updatedArtifacts);

        String revision = preferences.revision();
        journal.append(
                "MODULE_CONTROL",
                null,
                null,
                null,
                "refresh",
                added.isEmpty() ? "NO_CHANGES" : "APPENDED",
                "模块刷新完成：新增 " + added.size() + "，问题 " + issues.size()
        );
        return new ModuleRefreshResult(added, issues, revision);
    }

    private List<String> scanSourceDirectories() {
        if (!Files.isDirectory(moduleSourceDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(moduleSourceDirectory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(moduleSourceDirectory)) {
            return entries
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("module_"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描模块源码目录失败：" + moduleSourceDirectory, exception);
        }
    }

    private String sourceFingerprint(String sourceName) {
        Path directory = moduleSourceDirectory.resolve(sourceName).normalize();
        if (!directory.startsWith(moduleSourceDirectory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return "";
        }
        MessageDigest digest = sha256();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths
                    .filter(path -> !path.equals(directory))
                    .filter(path -> !path.normalize().startsWith(directory.resolve("target")))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .forEach(path -> {
                        String relative = directory.relativize(path).toString().replace('\\', '/');
                        digest.update(relative.getBytes(StandardCharsets.UTF_8));
                        try {
                            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && !Files.isSymbolicLink(path)) {
                                digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
                                digest.update(Long.toString(Files.getLastModifiedTime(path).toMillis())
                                        .getBytes(StandardCharsets.UTF_8));
                                digest.update(Files.readAllBytes(path));
                            }
                        } catch (IOException exception) {
                            throw new IllegalStateException("读取模块源码摘要失败：" + path, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("扫描模块源码摘要失败：" + directory, exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ArtifactFingerprint artifactFingerprint(Path jar) {
        try {
            return new ArtifactFingerprint(
                    sourceName(jar.getFileName().toString()),
                    Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis(),
                    fileDigest(jar)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块 JAR 摘要失败：" + jar, exception);
        }
    }

    private String sourceName(String jarFileName) {
        return jarFileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - 4)
                : jarFileName;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知加载错误" : message;
    }

    private String fileDigest(Path file) {
        MessageDigest digest = sha256();
        try {
            digest.update(Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块文件内容摘要失败：" + file, exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record ArtifactFingerprint(
            String sourceName,
            long size,
            long lastModifiedMillis,
            String contentHash
    ) {
    }
}
