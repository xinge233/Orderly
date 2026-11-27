package com.orderly.host.codegen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.SystemModulePolicy;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Host orchestration for the built-in module and motion generation studio.
 *
 * <p>The module owns prompts and model-output normalization. This service validates
 * declarative file proposals, records every file step, builds candidates in a
 * separate workspace, starts a mirror host, and applies accepted versions without
 * restarting the current application. Project source replacement moves old versions
 * into the application trash and records them in the version ledger.</p>
 */
@Service
public class CodegenService {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TEXT_FILES = 80;
    private static final long MAX_INPUT_BYTES = 700L * 1024L;
    private static final long MAX_STEP_BYTES = 400L * 1024L;
    private static final Pattern MODULE_ID = Pattern.compile("module_[a-z0-9_]+");
    private static final Pattern THEME_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final String MODULE_RESOURCE_ROOT = "src/main/resources/";
    private static final String MODULE_SPI_PATH =
            MODULE_RESOURCE_ROOT + "META-INF/services/com.orderly.kernel.OrderlyModule";
    private static final Set<String> MODULE_UI_PATHS = Set.of(
            MODULE_RESOURCE_ROOT + "orderly-ui/module.json",
            MODULE_RESOURCE_ROOT + "orderly-ui/module.js",
            MODULE_RESOURCE_ROOT + "orderly-ui/module.css"
    );
    private static final TypeReference<List<CodegenVersion>> VERSION_LIST =
            new TypeReference<>() {
            };
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".css", ".html", ".java", ".js", ".json", ".md", ".mjs",
            ".properties", ".py", ".svg", ".txt", ".xml"
    );

    private final ObjectMapper objectMapper;
    private final WorkspaceRuntime runtime;
    private final ContractCatalog catalog;
    private final ExecutionJournal journal;
    private final CodegenProposalRunner proposalRunner;
    private final CodegenPreviewService previewService;
    private final Path repositoryRoot;
    private final Path moduleSourceDirectory;
    private final Path themesDirectory;
    private final Path modulesDirectory;
    private final Path root;
    private final Path sessionsDirectory;
    private final Path workspacesDirectory;
    private final Path buildsDirectory;
    private final Path artifactsDirectory;
    private final Path versionsDirectory;
    private final Path trashDirectory;
    private final Path ledgerFile;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public CodegenService(
            ObjectMapper objectMapper,
            WorkspaceRuntime runtime,
            ContractCatalog catalog,
            ExecutionJournal journal,
            CodegenProposalRunner proposalRunner,
            CodegenPreviewService previewService,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.themes-dir:themes}") String themesDirectory,
            @Value("${orderly.modules-dir:target/orderly-modules}") String modulesDirectory
    ) {
        this.objectMapper = objectMapper;
        this.runtime = runtime;
        this.catalog = catalog;
        this.journal = journal;
        this.proposalRunner = proposalRunner;
        this.previewService = previewService;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
        this.themesDirectory = Path.of(themesDirectory).toAbsolutePath().normalize();
        this.modulesDirectory = Path.of(modulesDirectory).toAbsolutePath().normalize();
        this.repositoryRoot = commonProjectRoot(moduleSourceDirectory, themesDirectory);
        this.root = runtime.dataRoot().resolve("codegen").normalize();
        this.sessionsDirectory = root.resolve("sessions");
        this.workspacesDirectory = root.resolve("workspaces");
        this.buildsDirectory = root.resolve("builds");
        this.artifactsDirectory = root.resolve("artifacts");
        this.versionsDirectory = root.resolve("versions");
        this.trashDirectory = runtime.dataRoot().resolve("trash").resolve("codegen").normalize();
        this.ledgerFile = root.resolve("module-versions.json");
        initializeDirectories();
        resetStalePreviews();
        reconcileVersions();
    }

    /** List recent sessions without exposing full proposed file contents. */
    public List<SessionSummary> sessions() {
        if (!Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDirectory)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(this::readSession)
                    .sorted(Comparator.comparing(CodegenSession::updatedAt).reversed())
                    .map(SessionSummary::from)
                    .toList();
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "CODEGEN_SESSIONS_FAILED",
                    "读取生成会话失败"
            );
        }
    }

    /** Return one safe session projection. */
    public SessionView session(String sessionId) {
        return view(requireSession(sessionId));
    }

    /** Start one generation request and automatically build runnable candidates. */
    public SessionView generate(GenerateRequest request) {
        validateRequest(request);
        String mode = request.mode().trim();
        String target = trim(request.targetModuleId());
        String themeId = trim(request.themeId());
        String motionTarget = trim(request.motionTarget());
        Target resolved = resolveTarget(mode, target, motionTarget, themeId);
        Map<String, String> currentFiles = snapshotTextFiles(resolved.sourceRoot());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode);
        payload.put("prompt", request.prompt().trim());
        payload.put("targetModuleId", resolved.moduleId());
        payload.put("motionTarget", resolved.motionTarget());
        payload.put("themeId", resolved.themeId());
        payload.put("files", currentFiles);
        payload.put("catalog", request.moduleCatalog() == null ? List.of() : request.moduleCatalog());
        payload.put("motionScopes", request.motionScopes() == null ? List.of() : request.motionScopes());
        payload.put("motionNodes", request.motionNodes() == null ? List.of() : request.motionNodes());
        payload.put(
                "motionAnnotations",
                request.motionAnnotations() == null ? List.of() : request.motionAnnotations()
        );

        JsonNode proposal = proposalRunner.propose(payload);
        String moduleId = proposal.path("moduleId").asText("");
        moduleId = moduleId.isBlank() ? resolved.moduleId() : moduleId.trim();
        validateModuleId(mode, resolved, moduleId);
        String summary = proposal.path("summary").asText("").trim();
        if (summary.isBlank()) {
            throw invalidProposal("模型提案缺少摘要");
        }
        JsonNode rawSteps = proposal.path("steps");
        if (!rawSteps.isArray() || rawSteps.isEmpty() || rawSteps.size() > 48) {
            throw invalidProposal("模型提案必须包含 1 至 48 个文件步骤");
        }

        String sessionId = UUID.randomUUID().toString();
        Path workspace = workspacesDirectory.resolve(sessionId).resolve("source").normalize();
        copyDirectoryIfPresent(resolved.sourceRoot(), workspace);
        List<CodegenStep> steps = new ArrayList<>();
        long totalBytes = 0;
        Set<String> paths = new LinkedHashSet<>();
        for (int index = 0; index < rawSteps.size(); index++) {
            JsonNode rawStep = rawSteps.get(index);
            String path = safeProposalPath(rawStep.path("path").asText(""));
            path = normalizeProposalPath(mode, resolved, path);
            validateProposalPath(mode, resolved, moduleId, path);
            if (!paths.add(path)) {
                throw invalidProposal("模型提案包含重复文件路径：" + path);
            }
            String content = rawStep.path("content").asText(null);
            if (content == null) {
                throw invalidProposal("文件步骤缺少文本内容：" + path);
            }
            long bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_STEP_BYTES) {
                throw invalidProposal("单个生成文件超过 400 KiB：" + path);
            }
            totalBytes += bytes;
            if (totalBytes > 900L * 1024L) {
                throw invalidProposal("生成文件总量超过 900 KiB");
            }
            Path targetFile = workspace.resolve(path).normalize();
            requireInside(workspace, targetFile, "生成路径越过候选工作区");
            boolean existed = Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(targetFile);
            String before = existed ? readText(targetFile) : "";
            if (!existed && Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                throw invalidProposal("生成路径不是普通文件：" + path);
            }
            writeText(targetFile, content);
            steps.add(new CodegenStep(
                    "s" + (index + 1),
                    index + 1,
                    boundedTitle(rawStep.path("title").asText(""), path),
                    path,
                    "GENERATED",
                    existed,
                    before,
                    content
            ));
        }
        ensureProposalComplete(mode, resolved, moduleId, workspace);
        if ("module".equals(mode)) {
            normalizeGeneratedModule(workspace, moduleId, steps);
        }
        Map<String, Object> interfaces = extractInterfaces(workspace);
        String now = Instant.now().toString();
        CodegenSession session = new CodegenSession(
                SCHEMA_VERSION,
                sessionId,
                mode,
                resolved.motionTarget(),
                resolved.themeId(),
                resolved.moduleId().isBlank() ? moduleId : resolved.moduleId(),
                moduleId,
                request.prompt().trim(),
                summary,
                "GENERATED",
                List.copyOf(steps),
                interfaces,
                new BuildState("PENDING", "", "", "", ""),
                new PreviewState("STOPPED", "", ""),
                "",
                "",
                now,
                now
        );
        saveSession(session);
        journal.append(
                "CODEGEN",
                "MODULE_CONTROL",
                sessionId,
                null,
                SystemModulePolicy.CODEGEN_MODULE_ID,
                "generate",
                "GENERATED",
                "已生成候选文件步骤：" + steps.size()
        );
        if (requiresBuild(session)) {
            buildAsync(sessionId);
        } else {
            String finishedAt = Instant.now().toString();
            saveSession(session.withBuild(
                    new BuildState(
                            "READY",
                            "主题候选无需 JAR 构建",
                            "",
                            now,
                            finishedAt
                    ),
                    finishedAt
            ));
        }
        return view(requireSession(sessionId));
    }

    /** Queue a complete rebuild of the current workspace snapshot. */
    public SessionView rebuild(String sessionId) {
        CodegenSession session = requireSession(sessionId);
        if ("APPLIED".equals(session.status()) || "ROLLED_BACK".equals(session.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CODEGEN_SESSION_ALREADY_APPLIED",
                    "已应用会话必须从修改后的步骤重新生成候选，不能覆盖应用记录"
            );
        }
        if (session.steps().stream().noneMatch(step -> !"ROLLED_BACK".equals(step.status()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CODEGEN_SESSION_EMPTY",
                    "所有生成步骤均已回滚，没有可构建内容"
            );
        }
        buildAsync(sessionId);
        return view(requireSession(sessionId));
    }

    /** Start an isolated mirror host for the built candidate. */
    public SessionView preview(String sessionId) {
        CodegenSession session = requireSession(sessionId);
        if (!"READY".equals(session.build().status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CODEGEN_BUILD_NOT_READY",
                    "候选尚未构建成功，不能启动镜像预览"
            );
        }
        Path previewRoot = buildsDirectory
                .resolve(session.sessionId())
                .resolve("preview-" + UUID.randomUUID());
        Path previewModules = previewRoot.resolve("modules");
        Path previewThemes = previewRoot.resolve("themes");
        preparePreviewModules(session, previewModules);
        preparePreviewThemes(session, previewThemes);
        CodegenPreviewService.PreviewHandle handle = previewService.start(
                session.sessionId(),
                previewModules,
                previewThemes,
                previewRoot.resolve("data")
        );
        CodegenSession updated = session.withPreview(
                new PreviewState("READY", handle.url(), Instant.now().toString())
        );
        saveSession(updated);
        journal.append(
                "CODEGEN",
                "MODULE_CONTROL",
                sessionId,
                null,
                SystemModulePolicy.CODEGEN_MODULE_ID,
                "preview",
                "READY",
                "候选镜像宿主已启动"
        );
        return view(updated);
    }

    /** Apply an accepted candidate source version without restarting this process. */
    public SessionView apply(String sessionId) {
        CodegenSession session = requireSession(sessionId);
        synchronized (lockFor(sessionId)) {
            session = requireSession(sessionId);
            if (!"READY".equals(session.build().status())
                    || !"READY".equals(session.preview().status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CODEGEN_REVIEW_REQUIRED",
                        "必须先构建成功并打开候选镜像预览，才能应用版本"
                );
            }
            if (!session.applyVersionId().isBlank()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CODEGEN_ALREADY_APPLIED",
                        "该生成会话已经应用"
                );
            }
            Path workspace = workspaceFor(sessionId);
            Path targetRoot = targetRoot(session);
            String versionId = UUID.randomUUID().toString();
            Path versionSource = versionsDirectory.resolve(versionId).resolve("source");
            if (Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(targetRoot, versionSource);
                moveToTrash(targetRoot, versionId, targetRoot.getFileName().toString());
            }
            try {
                copyDirectory(workspace, targetRoot);
            } catch (RuntimeException exception) {
                if (!Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(versionSource, LinkOption.NOFOLLOW_LINKS)) {
                    copyDirectory(versionSource, targetRoot);
                }
                throw exception;
            }
            String sourceHash = hashTree(targetRoot);
            List<CodegenVersion> versions = new ArrayList<>(loadVersions());
            String targetKey = versionTargetKey(session);
            versions.replaceAll(version -> targetKey.equals(version.targetKey())
                    && Set.of("ACTIVE", "PENDING_RESTART", "READY")
                    .contains(version.status())
                    ? version.withStatus("DEPRECATED")
                    : version);
            versions.add(new CodegenVersion(
                    versionId,
                    targetKey,
                    session.moduleId(),
                    session.mode(),
                    "PENDING_RESTART",
                    session.sessionId(),
                    sourceHash,
                    Instant.now().toString(),
                    repositoryRelative(targetRoot)
            ));
            saveVersions(versions);
            CodegenSession applied = session.withApplied(
                    "APPLIED",
                    versionId,
                    Instant.now().toString()
            );
            saveSession(applied);
            previewService.stop(sessionId);
            journal.append(
                    "CODEGEN",
                    "MODULE_CONTROL",
                    sessionId,
                    null,
                    SystemModulePolicy.CODEGEN_MODULE_ID,
                    "apply",
                    "PENDING_RESTART",
                    "候选源码已应用，当前进程仍运行旧版本"
            );
            return view(requireSession(sessionId));
        }
    }

    /**
     * Roll back one step and automatically roll back all later steps in reverse order.
     */
    public SessionView rollbackStep(String sessionId, String stepId) {
        CodegenSession current = requireSession(sessionId);
        synchronized (lockFor(sessionId)) {
            CodegenSession session = requireSession(sessionId);
            int targetIndex = -1;
            for (int index = 0; index < session.steps().size(); index++) {
                if (session.steps().get(index).stepId().equals(stepId)) {
                    targetIndex = index;
                    break;
                }
            }
            if (targetIndex < 0) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CODEGEN_STEP_NOT_FOUND",
                        "生成步骤不存在：" + stepId
                );
            }
            boolean applied = !session.applyVersionId().isBlank();
            Path workspace = workspaceFor(sessionId);
            Path liveRoot = applied ? targetRoot(session) : null;
            List<CodegenStep> steps = new ArrayList<>(session.steps());
            boolean changed = false;
            for (int index = steps.size() - 1; index >= targetIndex; index--) {
                CodegenStep step = steps.get(index);
                if ("ROLLED_BACK".equals(step.status())) {
                    continue;
                }
                if (applied && !liveContentMatches(liveRoot.resolve(step.path()), step.afterContent())) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "CODEGEN_SOURCE_CONFLICT",
                            "目标模块源码已在应用后发生变化，拒绝覆盖回滚：" + step.path()
                    );
                }
                restoreFile(workspace, sessionId, step);
                if (applied) {
                    restoreFile(liveRoot, sessionId, step);
                }
                steps.set(index, step.withStatus("ROLLED_BACK"));
                changed = true;
            }
            if (!changed) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CODEGEN_STEP_ALREADY_ROLLED_BACK",
                        "该步骤及其后续步骤均已回滚"
                );
            }
            boolean allRolledBack = steps.stream()
                    .allMatch(step -> "ROLLED_BACK".equals(step.status()));
            String status = allRolledBack
                    ? "ROLLED_BACK"
                    : applied ? "PARTIALLY_ROLLED_BACK" : "GENERATED";
            previewService.stop(sessionId);
            CodegenSession updated = session.withRollback(
                    steps,
                    status,
                    allRolledBack
                            ? "ROLLED_BACK"
                            : "STALE",
                    Instant.now().toString()
            );
            saveSession(updated);
            if (applied) {
                updateVersionStatus(
                        session.applyVersionId(),
                        allRolledBack ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK"
                );
            }
            journal.append(
                    "CODEGEN",
                    "MODULE_CONTROL",
                    sessionId,
                    stepId,
                    SystemModulePolicy.CODEGEN_MODULE_ID,
                    "rollback",
                    "ROLLED_BACK",
                    "已连带回滚后续生成步骤"
            );
            if (!allRolledBack && !applied) {
                buildAsync(sessionId);
            }
            return view(requireSession(sessionId));
        }
    }

    /** Return before and after content for one explicit diff review. */
    public StepDiff stepDiff(String sessionId, String stepId) {
        CodegenStep step = requireSession(sessionId).steps().stream()
                .filter(candidate -> candidate.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CODEGEN_STEP_NOT_FOUND",
                        "生成步骤不存在：" + stepId
                ));
        return new StepDiff(
                step.stepId(),
                step.path(),
                step.existedBefore(),
                step.beforeContent(),
                step.afterContent(),
                step.status()
        );
    }

    /** Return the recognized source version ledger. */
    public List<CodegenVersion> versions() {
        return loadVersions().stream()
                .sorted(Comparator.comparing(CodegenVersion::createdAt).reversed())
                .toList();
    }

    private void buildAsync(String sessionId) {
        synchronized (lockFor(sessionId)) {
            CodegenSession session = requireSession(sessionId);
            saveSession(session.withBuild(
                    new BuildState("QUEUED", "等待构建", "", "", ""),
                    session.updatedAt()
            ));
        }
        workers.submit(() -> {
            synchronized (lockFor(sessionId)) {
                runBuild(sessionId);
            }
        });
    }

    private void runBuild(String sessionId) {
        CodegenSession session = requireSession(sessionId);
        String startedAt = Instant.now().toString();
        saveSession(session.withBuild(
                new BuildState("BUILDING", "正在构建候选", "", startedAt, ""),
                startedAt
        ));
        try {
            if (!requiresBuild(session)) {
                saveSession(session.withBuild(
                        new BuildState(
                                "READY",
                                "主题候选无需 JAR 构建",
                                "",
                                startedAt,
                                Instant.now().toString()
                        ),
                        Instant.now().toString()
                ));
                return;
            }
            Path workspace = workspaceFor(sessionId);
            Path buildRoot = buildsDirectory
                    .resolve(sessionId)
                    .resolve("compile-" + UUID.randomUUID())
                    .resolve(session.moduleId())
                    .normalize();
            copyDirectory(workspace, buildRoot);
            patchParentRelativePath(buildRoot.resolve("pom.xml"));
            Path jar = runMavenPackage(buildRoot, session.moduleId());
            Path artifactDirectory = artifactsDirectory.resolve(sessionId);
            Files.createDirectories(artifactDirectory);
            Path artifact = artifactDirectory.resolve(session.moduleId() + ".jar");
            Files.copy(jar, artifact, StandardCopyOption.REPLACE_EXISTING);
            CodegenSession ready = requireSession(sessionId).withBuild(
                    new BuildState(
                            "READY",
                            "候选构建成功",
                            root.relativize(artifact).toString().replace('\\', '/'),
                            startedAt,
                            Instant.now().toString()
                    ),
                    Instant.now().toString()
            );
            saveSession(ready);
            journal.append(
                    "CODEGEN",
                    "MODULE_CONTROL",
                    sessionId,
                    null,
                    SystemModulePolicy.CODEGEN_MODULE_ID,
                    "build",
                    "READY",
                    "候选模块构建成功"
            );
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            CodegenSession failed = requireSession(sessionId).withBuild(
                    new BuildState(
                            "FAILED",
                            message,
                            "",
                            startedAt,
                            Instant.now().toString()
                    ),
                    Instant.now().toString()
            );
            saveSession(failed);
            journal.append(
                    "CODEGEN",
                    "MODULE_CONTROL",
                    sessionId,
                    null,
                    SystemModulePolicy.CODEGEN_MODULE_ID,
                    "build",
                    "FAILED",
                    message
            );
        } catch (IOException exception) {
            String message = "写入候选构建产物失败";
            CodegenSession failed = requireSession(sessionId).withBuild(
                    new BuildState(
                            "FAILED",
                            message,
                            "",
                            startedAt,
                            Instant.now().toString()
                    ),
                    Instant.now().toString()
            );
            saveSession(failed);
        }
    }

    private Path runMavenPackage(Path buildRoot, String moduleId) {
        Path pom = buildRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("候选模块缺少 pom.xml");
        }
        String maven = mavenExecutable();
        List<String> command = new ArrayList<>();
        command.add(maven);
        command.add("-q");
        command.add("-f");
        command.add(pom.toString());
        command.add("-DskipTests");
        Path localRepository = repositoryRoot.resolve(".m2-repository");
        if (Files.isDirectory(localRepository, LinkOption.NOFOLLOW_LINKS)) {
            command.add("-Dmaven.repo.local=" + localRepository);
        }
        command.add("package");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repositoryRoot.toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readNBytes(1_000_000);
                } catch (IOException exception) {
                    throw new IllegalStateException("读取 Maven 输出失败", exception);
                }
            });
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("候选模块构建超时");
            }
            byte[] output = outputFuture.join();
            if (process.exitValue() != 0) {
                String text = new String(output, StandardCharsets.UTF_8).trim();
                throw new IllegalStateException(
                        text.isBlank() ? "Maven 候选构建失败" : tail(text, 1800)
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 Maven 候选构建", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("候选模块构建已中断");
        }
        Path target = buildRoot.resolve("target");
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("候选构建未生成 target 目录");
        }
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .filter(path -> !path.getFileName().toString().contains("-javadoc"))
                    .filter(path -> !path.getFileName().toString().endsWith(".original"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.comparingLong(path -> path.toFile().length()))
                    .orElseThrow(() -> new IllegalStateException(
                            "候选构建未找到模块 JAR：" + moduleId
                    ));
        } catch (IOException exception) {
            throw new IllegalStateException("读取候选构建产物失败", exception);
        }
    }

    private void preparePreviewModules(CodegenSession session, Path destination) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("候选预览模块目录已存在");
        }
        copyDirectory(modulesDirectory, destination);
        if (!session.build().jarPath().isBlank()) {
            Path candidate = root.resolve(session.build().jarPath()).normalize();
            requireInside(root, candidate, "候选 JAR 越过生成数据目录");
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("候选 JAR 不存在");
            }
            Path target = destination.resolve(session.moduleId() + ".jar");
            try {
                Files.createDirectories(target.getParent());
                Files.copy(candidate, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("准备候选预览 JAR 失败", exception);
            }
        }
    }

    private void preparePreviewThemes(CodegenSession session, Path destination) {
        if ("motion".equals(session.mode())
                && "framework".equals(session.motionTarget())) {
            Path themeTarget = destination.resolve(session.themeId()).normalize();
            copyDirectory(workspaceFor(session.sessionId()), themeTarget);
            return;
        }
        copyDirectory(themesDirectory, destination);
    }

    private void validateRequest(GenerateRequest request) {
        if (request == null) {
            throw invalidRequest("生成请求不能为空");
        }
        String mode = trim(request.mode());
        if (!Set.of("module", "motion").contains(mode)) {
            throw invalidRequest("mode 必须是 module 或 motion");
        }
        String prompt = trim(request.prompt());
        if (prompt.isBlank() || prompt.length() > 8000) {
            throw invalidRequest("prompt 必须包含 1 至 8000 个字符");
        }
        validateMotionNodes(request.motionNodes());
        validateMotionAnnotations(request.motionAnnotations());
    }

    private void validateMotionNodes(List<Map<String, Object>> nodes) {
        if (nodes == null) return;
        if (nodes.size() > 120) {
            throw invalidRequest("motionNodes 不能超过 120 项");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String id = trim(stringValue(node.get("node")));
            if (!id.matches("^[a-z0-9_-]{1,80}$") || !ids.add(id)) {
                throw invalidRequest("motionNodes 包含非法或重复 node");
            }
            if (!trim(stringValue(node.get("tag"))).matches("^[a-z][a-z0-9-]{0,32}$")) {
                throw invalidRequest("motionNodes 包含非法 tag");
            }
            requireBoundedText(node.get("selector"), 300, "motionNodes.selector");
            requireBoundedText(node.get("role"), 40, "motionNodes.role");
            requireBoundedText(node.get("classes"), 240, "motionNodes.classes");
            requireBoundedText(node.get("text"), 160, "motionNodes.text");
            requireBoundedText(node.get("sourceHint"), 300, "motionNodes.sourceHint");
        }
    }

    private void validateMotionAnnotations(List<Map<String, Object>> annotations) {
        if (annotations == null) return;
        if (annotations.size() > 16) {
            throw invalidRequest("motionAnnotations 不能超过 16 项");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> annotation : annotations) {
            String id = trim(stringValue(annotation.get("annotationId")));
            if (!id.matches("^annotation-[0-9]{1,4}$") || !ids.add(id)) {
                throw invalidRequest("motionAnnotations 包含非法或重复 annotationId");
            }
            String kind = trim(stringValue(annotation.get("kind")));
            if (!Set.of("rect", "arrow", "pen", "text").contains(kind)) {
                throw invalidRequest("motionAnnotations.kind 非法");
            }
            Object rawPoints = annotation.get("points");
            if (!(rawPoints instanceof List<?> points)) {
                throw invalidRequest("motionAnnotations.points 必须是数组");
            }
            if (points.isEmpty() || points.size() > 128) {
                throw invalidRequest("motionAnnotations.points 数量必须在 1 至 128 之间");
            }
            for (Object rawPoint : points) {
                if (!(rawPoint instanceof List<?> point) || point.size() != 2) {
                    throw invalidRequest("motionAnnotations.points 项必须包含两个坐标");
                }
                validateCoordinate(point.get(0));
                validateCoordinate(point.get(1));
            }
            requireBoundedText(annotation.get("text"), 200, "motionAnnotations.text");
            requireBoundedText(annotation.get("targetNode"), 80, "motionAnnotations.targetNode");
            requireBoundedText(annotation.get("selector"), 300, "motionAnnotations.selector");
            requireBoundedText(annotation.get("sourceHint"), 300, "motionAnnotations.sourceHint");
            Object rawNodes = annotation.get("nodes");
            if (rawNodes instanceof List<?> nestedNodes) {
                if (nestedNodes.size() > 8) {
                    throw invalidRequest("motionAnnotations.nodes 不能超过 8 项");
                }
                validateMotionNodes(nestedNodes.stream()
                        .filter(Map.class::isInstance)
                        .map(value -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mapped = (Map<String, Object>) value;
                            return mapped;
                        })
                        .toList());
            }
        }
    }

    private void validateCoordinate(Object value) {
        if (!(value instanceof Number number)) {
            throw invalidRequest("motionAnnotations 坐标必须是数字");
        }
        double coordinate = number.doubleValue();
        if (!Double.isFinite(coordinate) || coordinate < 0 || coordinate > 1) {
            throw invalidRequest("motionAnnotations 坐标必须位于 0 至 1");
        }
    }

    private void requireBoundedText(Object value, int limit, String field) {
        if (value == null) return;
        if (!(value instanceof String textValue) || textValue.length() > limit) {
            throw invalidRequest(field + " 不是有效文本或超过长度限制");
        }
    }

    private String stringValue(Object value) {
        return value instanceof String textValue ? textValue : "";
    }

    private Target resolveTarget(
            String mode,
            String targetModule,
            String motionTarget,
            String themeId
    ) {
        if ("module".equals(mode)) {
            if (targetModule.isBlank()) {
                return new Target("", "", "", null);
            }
            Path source = requireBusinessModuleSource(targetModule);
            return new Target(targetModule, "module", "", source);
        }
        if ("framework".equals(motionTarget)) {
            String resolvedTheme = themeId.isBlank() ? "paper" : themeId;
            if (!THEME_ID.matcher(resolvedTheme).matches()) {
                throw invalidRequest("themeId 非法");
            }
            Path theme = themesDirectory.resolve(resolvedTheme).normalize();
            requireInside(themesDirectory, theme, "主题路径越过主题目录");
            if (!Files.isDirectory(theme, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(theme)) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CODEGEN_THEME_NOT_FOUND",
                        "目标主题不存在：" + resolvedTheme
                );
            }
            return new Target("", "framework", resolvedTheme, theme);
        }
        if (!"module".equals(motionTarget)) {
            throw invalidRequest("motionTarget 必须是 framework 或 module");
        }
        Path source = requireBusinessModuleSource(targetModule);
        return new Target(targetModule, "module", "", source);
    }

    private Path requireBusinessModuleSource(String moduleId) {
        if (!MODULE_ID.matcher(moduleId).matches()
                || SystemModulePolicy.hasReservedPrefix(moduleId)) {
            throw invalidRequest("模块 ID 必须是普通业务模块");
        }
        Path source = moduleSourceDirectory.resolve(moduleId).normalize();
        requireInside(moduleSourceDirectory, source, "模块源码路径越过 Module 目录");
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CODEGEN_MODULE_NOT_FOUND",
                    "目标模块源码不存在：" + moduleId
            );
        }
        return source;
    }

    private void validateModuleId(String mode, Target target, String moduleId) {
        if ("motion".equals(mode) && "framework".equals(target.motionTarget())) {
            return;
        }
        if (!MODULE_ID.matcher(moduleId).matches()
                || SystemModulePolicy.hasReservedPrefix(moduleId)) {
            throw invalidProposal("模型返回了非法模块 ID：" + moduleId);
        }
        if (!target.moduleId().isBlank() && !target.moduleId().equals(moduleId)) {
            throw invalidProposal("模型不得改变已选目标模块 ID");
        }
        if ("module".equals(mode) && target.sourceRoot() == null
                && Files.exists(moduleSourceDirectory.resolve(moduleId), LinkOption.NOFOLLOW_LINKS)) {
            throw invalidProposal("新模块 ID 已存在：" + moduleId);
        }
    }

    private void validateProposalPath(
            String mode,
            Target target,
            String moduleId,
            String path
    ) {
        if ("motion".equals(mode) && "framework".equals(target.motionTarget())) {
            if (!Set.of("motion.css", "tokens.css").contains(path)) {
                throw invalidProposal("框架动效只允许修改 tokens.css 或 motion.css");
            }
            return;
        }
        if ("motion".equals(mode)) {
            if (!MODULE_UI_PATHS.contains(path)) {
                throw invalidProposal(
                        "模块动效只允许修改本模块 src/main/resources/orderly-ui 文件"
                );
            }
            return;
        }
        if (!(path.equals("pom.xml")
                || path.equals(MODULE_SPI_PATH)
                || path.equals(
                        MODULE_RESOURCE_ROOT + "orderly-modules/" + moduleId + ".json"
                )
                || MODULE_UI_PATHS.contains(path)
                || path.startsWith(MODULE_RESOURCE_ROOT)
                || path.startsWith("src/main/java/")
                || path.startsWith("src/test/")
                || path.startsWith("ai/"))) {
            throw invalidProposal("模块生成路径不在允许范围：" + path);
        }
        if (path.equals(MODULE_SPI_PATH)) {
            return;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".java")
                || lower.endsWith(".json")
                || lower.endsWith(".js")
                || lower.endsWith(".mjs")
                || lower.endsWith(".css")
                || lower.endsWith(".py")
                || lower.endsWith(".md")
                || lower.endsWith(".txt")
                || lower.endsWith(".xml")
                || lower.endsWith(".properties")
                || lower.endsWith(".svg"))) {
            throw invalidProposal("模块生成不支持该文本文件类型：" + path);
        }
    }

    private void ensureProposalComplete(
            String mode,
            Target target,
            String moduleId,
            Path workspace
    ) {
        if ("motion".equals(mode)) {
            if ("framework".equals(target.motionTarget())
                    && !Files.isRegularFile(workspace.resolve("motion.css"))) {
                throw invalidProposal("框架动效候选缺少 motion.css");
            }
            return;
        }
        List<String> required = List.of(
                "pom.xml",
                MODULE_SPI_PATH,
                MODULE_RESOURCE_ROOT + "orderly-modules/" + moduleId + ".json",
                MODULE_RESOURCE_ROOT + "orderly-ui/module.json",
                MODULE_RESOURCE_ROOT + "orderly-ui/module.js",
                MODULE_RESOURCE_ROOT + "orderly-ui/module.css"
        );
        for (String path : required) {
            if (!Files.isRegularFile(workspace.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                throw invalidProposal("候选模块缺少必需文件：" + path);
            }
        }
        boolean hasJava = false;
        Path javaRoot = workspace.resolve("src/main/java");
        if (Files.isDirectory(javaRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.walk(javaRoot)) {
                hasJava = files.anyMatch(path ->
                        path.getFileName().toString().endsWith(".java")
                                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                );
            } catch (IOException exception) {
                throw new IllegalStateException("检查候选 Java 文件失败", exception);
            }
        }
        if (!hasJava) {
            throw invalidProposal("候选模块缺少 Java 执行器源码");
        }
    }

    private Map<String, Object> extractInterfaces(Path workspace) {
        List<Map<String, Object>> motionHooks = extractMotionHooks(workspace);
        Path contractDirectory = workspace.resolve("src/main/resources/orderly-modules");
        if (!Files.isDirectory(contractDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of(
                    "actions", List.of(),
                    "queries", List.of(),
                    "plannerRules", List.of(),
                    "motionHooks", motionHooks
            );
        }
        try (Stream<Path> files = Files.list(contractDirectory)) {
            Optional<Path> contract = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .findFirst();
            if (contract.isEmpty()) {
                return Map.of(
                        "actions", List.of(),
                        "queries", List.of(),
                        "plannerRules", List.of(),
                        "motionHooks", motionHooks
                );
            }
            JsonNode root = objectMapper.readTree(Files.readString(contract.orElseThrow()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("actions", objectMapper.convertValue(
                    root.path("actions"),
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            ));
            result.put("queries", objectMapper.convertValue(
                    root.path("queries"),
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            ));
            result.put("plannerRules", objectMapper.convertValue(
                    root.path("plannerRules"),
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            ));
            result.put("motionHooks", motionHooks);
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("读取候选模块接口失败", exception);
        }
    }

    private List<Map<String, Object>> extractMotionHooks(Path workspace) {
        Pattern hook = Pattern.compile(
                "data-motion-scope\\s*=\\s*[\"']([a-z0-9_-]{1,80})[\"']",
                Pattern.CASE_INSENSITIVE
        );
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(workspace)) {
            for (Path file : files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".js")
                                || name.endsWith(".mjs")
                                || name.endsWith(".html")
                                || name.endsWith(".css");
                    })
                    .limit(80)
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = hook.matcher(text);
                while (matcher.find() && scopes.size() < 32) {
                    scopes.add(matcher.group(1));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("扫描候选动效挂点失败", exception);
        }
        return scopes.stream()
                .map(scope -> Map.<String, Object>of(
                        "scope", scope,
                        "state", "candidate",
                        "phase", "declared"
                ))
                .toList();
    }

    private Map<String, String> snapshotTextFiles(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        long total = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> ordered = files
                    .filter(path -> !path.equals(sourceRoot))
                    .filter(path -> !path.normalize().startsWith(
                            sourceRoot.resolve("target").normalize()
                    ))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(this::isTextFile)
                    .sorted(Comparator.comparing(path ->
                            sourceRoot.relativize(path).toString()
                    ))
                    .limit(MAX_TEXT_FILES)
                    .toList();
            for (Path file : ordered) {
                long size = Files.size(file);
                if (size > 120_000 || total + size > MAX_INPUT_BYTES) {
                    continue;
                }
                String relative = sourceRoot.relativize(file)
                        .toString()
                        .replace('\\', '/');
                result.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                total += size;
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("读取目标模块文本快照失败", exception);
        }
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith)
                || name.equals("pom.xml");
    }

    private void patchParentRelativePath(Path pom) {
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            Files.writeString(
                    pom,
                    normalizeGeneratedPom(text),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("准备候选 Maven 父工程路径失败", exception);
        }
    }

    private String normalizeGeneratedPom(String pom) {
        return normalizeGeneratedPom(
                pom,
                repositoryRoot.resolve("pom.xml").toString()
        );
    }

    private String normalizeGeneratedPom(String pom, String parentPath) {
        try {
            ParentCoordinates coordinates = parentCoordinates(
                    Files.readString(
                            repositoryRoot.resolve("pom.xml"),
                            StandardCharsets.UTF_8
                    )
            );
            return ensureKernelDependencyVersion(
                    ensureParentRelativePath(pom, parentPath, coordinates)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取仓库根 pom.xml 失败", exception);
        }
    }

    private void normalizeGeneratedModule(
            Path workspace,
            String moduleId,
            List<CodegenStep> steps
    ) {
        Path pom = workspace.resolve("pom.xml");
        if (Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            String normalizedPom = ensureModuleArtifactId(
                    normalizeGeneratedPom(readText(pom), "../../pom.xml"),
                    moduleId
            );
            writeText(pom, normalizedPom);
            replaceStepContent(steps, "pom.xml", normalizedPom);
        }

        Path manifest = workspace.resolve(MODULE_RESOURCE_ROOT + "orderly-ui/module.json");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            try {
                String normalizedManifest = normalizeModuleUiManifest(
                        readText(manifest),
                        moduleId,
                        firstContractQueryId(workspace)
                );
                writeText(manifest, normalizedManifest);
                replaceStepContent(
                        steps,
                        MODULE_RESOURCE_ROOT + "orderly-ui/module.json",
                        normalizedManifest
                );
            } catch (IllegalArgumentException | IOException exception) {
                throw invalidProposal(
                        "候选模块 UI 清单不合格：" + safeMessage(exception)
                );
            }
        }

        Path script = workspace.resolve(MODULE_RESOURCE_ROOT + "orderly-ui/module.js");
        if (Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)) {
            try {
                requireDefaultModuleExport(readText(script));
            } catch (IllegalArgumentException exception) {
                throw invalidProposal("候选模块 UI 脚本不合格：" + exception.getMessage());
            }
        }
    }

    private void replaceStepContent(
            List<CodegenStep> steps,
            String path,
            String content
    ) {
        for (int index = 0; index < steps.size(); index++) {
            CodegenStep step = steps.get(index);
            if (path.equals(step.path())) {
                steps.set(index, step.withAfterContent(content));
                return;
            }
        }
    }

    private String firstContractQueryId(Path workspace) {
        Path directory = workspace.resolve("src/main/resources/orderly-modules");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try (Stream<Path> files = Files.list(directory)) {
            Optional<Path> contract = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .findFirst();
            if (contract.isEmpty()) {
                return "";
            }
            JsonNode root = objectMapper.readTree(readText(contract.orElseThrow()));
            JsonNode query = root.path("queries").path(0);
            return textValue(query.path("id"));
        } catch (IOException exception) {
            throw invalidProposal("无法读取候选模块契约中的 Query");
        }
    }

    static String normalizeModuleUiManifest(
            String content,
            String moduleId,
            String fallbackQueryId
    ) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(content);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("module.json 必须是 JSON 对象");
        }
        JsonNode view = root.path("view").isObject() ? root.path("view") : root;

        String viewId = textValue(view.path("id"));
        if (viewId.isBlank()) {
            viewId = textValue(root.path("id"));
        }
        if (viewId.isBlank()) {
            viewId = derivedViewId(moduleId);
        }
        if (!viewId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("view.id 非法：" + viewId);
        }

        String caption = firstText(
                textValue(view.path("caption")),
                textValue(view.path("title")),
                textValue(root.path("title")),
                moduleId
        );
        String icon = firstText(
                textValue(view.path("icon")),
                "spark"
        );
        String script = uiAssetName(root, "script", "entry", "js");
        String style = uiAssetName(root, "style", "styles", "css");

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("version", 1);
        normalized.put("view", Map.of(
                "id", viewId,
                "caption", caption,
                "icon", icon
        ));

        JsonNode rawCard = root.path("card");
        Map<String, Object> card = new LinkedHashMap<>();
        if (rawCard.isObject() || !fallbackQueryId.isBlank()) {
            card.put("title", firstText(
                    textValue(rawCard.path("title")),
                    caption
            ));
            card.put("description", firstText(
                    textValue(rawCard.path("description")),
                    textValue(root.path("description")),
                    "由模块提供的数据摘要。"
            ));
            card.put("badge", firstText(
                    textValue(rawCard.path("badge")),
                    "模块数据"
            ));
            card.put("icon", firstText(
                    textValue(rawCard.path("icon")),
                    icon
            ));
            card.put(
                    "queryId",
                    firstText(
                            textValue(rawCard.path("queryId")),
                            fallbackQueryId
                    )
            );
        }
        normalized.put("card", card.isEmpty() ? null : Map.copyOf(card));
        normalized.put(
                "examples",
                root.path("examples").isArray()
                        ? root.path("examples").deepCopy()
                        : List.of()
        );
        normalized.put(
                "settings",
                root.path("settings").isArray()
                        ? root.path("settings").deepCopy()
                        : List.of()
        );
        if (root.path("icons").isObject()) {
            normalized.put("icons", root.path("icons").deepCopy());
        }
        normalized.put("script", script);
        normalized.put("style", style);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized)
                + System.lineSeparator();
    }

    static String requireDefaultModuleExport(String script) {
        if (!Pattern.compile("(?m)^\\s*export\\s+default\\s+")
                .matcher(script)
                .find()) {
            throw new IllegalArgumentException(
                    "module.js 必须包含顶层 `export default` 挂载函数"
            );
        }
        return script;
    }

    private static String uiAssetName(
            JsonNode root,
            String primary,
            String legacy,
            String extension
    ) {
        String value = textValue(root.path(primary));
        if (value.isBlank() && "styles".equals(legacy)) {
            JsonNode styles = root.path(legacy);
            if (styles.isArray() && !styles.isEmpty()) {
                value = textValue(styles.get(0));
            }
        } else if (value.isBlank()) {
            value = textValue(root.path(legacy));
        }
        if (value.isBlank()) {
            value = "module." + extension;
        }
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || !value.toLowerCase(java.util.Locale.ROOT)
                .endsWith("." + extension)) {
            throw new IllegalArgumentException(
                    extension + " 资源名非法：" + value
            );
        }
        return value;
    }

    private static String derivedViewId(String moduleId) {
        String value = moduleId.startsWith("module_")
                ? moduleId.substring("module_".length())
                : moduleId;
        value = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (value.isBlank()) {
            return "generated-module";
        }
        if (!Character.isLetter(value.charAt(0))) {
            value = "view-" + value;
        }
        return value.substring(0, Math.min(value.length(), 64));
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static String ensureParentRelativePath(
            String pom,
            String rootPom,
            ParentCoordinates coordinates
    ) {
        Matcher parent = Pattern.compile(
                "(?s)<parent\\b[^>]*>.*?</parent>"
        ).matcher(pom);
        if (!parent.find()) {
            throw new IllegalStateException("候选 pom.xml 缺少 parent 定义");
        }
        String normalizedParent = "<parent>"
                + System.lineSeparator()
                + "    <groupId>" + xmlEscape(coordinates.groupId()) + "</groupId>"
                + System.lineSeparator()
                + "    <artifactId>" + xmlEscape(coordinates.artifactId()) + "</artifactId>"
                + System.lineSeparator()
                + "    <version>" + xmlEscape(coordinates.version()) + "</version>"
                + System.lineSeparator()
                + "    <relativePath>" + xmlEscape(rootPom) + "</relativePath>"
                + System.lineSeparator()
                + "</parent>";
        return pom.substring(0, parent.start())
                + normalizedParent
                + pom.substring(parent.end());
    }

    static String ensureKernelDependencyVersion(String pom) {
        Matcher dependency = Pattern.compile(
                "(?s)<dependency\\b[^>]*>.*?</dependency>"
        ).matcher(pom);
        while (dependency.find()) {
            String body = dependency.group();
            if (!elementValue(body, "groupId").equals("com.orderly")
                    || !elementValue(body, "artifactId").equals("orderly-kernel")) {
                continue;
            }
            Matcher version = Pattern.compile(
                    "(?s)<version\\b[^>]*>.*?</version>"
            ).matcher(body);
            String normalized = version.find()
                    ? version.replaceFirst(Matcher.quoteReplacement(
                            "<version>${project.version}</version>"
                    ))
                    : body.replace(
                            elementTag(body, "artifactId"),
                            elementTag(body, "artifactId")
                                    + System.lineSeparator()
                                    + "            <version>${project.version}</version>"
                    );
            return pom.substring(0, dependency.start())
                    + normalized
                    + pom.substring(dependency.end());
        }
        throw new IllegalStateException(
                "候选 pom.xml 缺少 com.orderly:orderly-kernel 依赖"
        );
    }

    static String ensureModuleArtifactId(String pom, String moduleId) {
        Matcher parent = Pattern.compile(
                "(?s)<parent\\b[^>]*>.*?</parent>"
        ).matcher(pom);
        int searchStart = parent.find() ? parent.end() : 0;
        int dependencies = pom.indexOf("<dependencies", searchStart);
        Matcher artifact = Pattern.compile(
                "(?s)<artifactId\\b[^>]*>[^<]*</artifactId>"
        ).matcher(pom);
        if (!artifact.find(searchStart)
                || (dependencies >= 0 && artifact.start() > dependencies)) {
            throw new IllegalStateException("候选 pom.xml 缺少模块 artifactId");
        }
        return pom.substring(0, artifact.start())
                + "<artifactId>" + xmlEscape(moduleId) + "</artifactId>"
                + pom.substring(artifact.end());
    }

    private static String elementValue(String xml, String element) {
        Matcher matcher = Pattern.compile(
                "(?s)<" + Pattern.quote(element) + "\\b[^>]*>([^<]*)</"
                        + Pattern.quote(element) + ">"
        ).matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String elementTag(String xml, String element) {
        Matcher matcher = Pattern.compile(
                "(?s)<" + Pattern.quote(element) + "\\b[^>]*>.*?</"
                        + Pattern.quote(element) + ">"
        ).matcher(xml);
        if (!matcher.find()) {
            throw new IllegalStateException("候选 pom.xml 缺少 " + element);
        }
        return matcher.group();
    }

    static ParentCoordinates parentCoordinates(String rootPom) {
        String withoutParent = Pattern.compile(
                "(?s)<parent\\b[^>]*>.*?</parent>"
        ).matcher(rootPom).replaceFirst("");
        return new ParentCoordinates(
                topLevelValue(withoutParent, "groupId"),
                topLevelValue(withoutParent, "artifactId"),
                topLevelValue(withoutParent, "version")
        );
    }

    private static String topLevelValue(String pom, String element) {
        String value = elementValue(pom, element);
        if (value.isBlank()) {
            throw new IllegalStateException("根 pom.xml 缺少 " + element);
        }
        return value;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String mavenExecutable() {
        String home = System.getenv("MAVEN_HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("M2_HOME");
        }
        if (home != null && !home.isBlank()) {
            Path executable = Path.of(home, "bin", isWindows() ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                return executable.toString();
            }
        }
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private boolean requiresBuild(CodegenSession session) {
        return !("motion".equals(session.mode())
                && "framework".equals(session.motionTarget()));
    }

    private Path workspaceFor(String sessionId) {
        Path workspace = workspacesDirectory.resolve(sessionId).resolve("source").normalize();
        requireInside(workspacesDirectory, workspace, "候选工作区路径越界");
        return workspace;
    }

    private Path targetRoot(CodegenSession session) {
        if ("motion".equals(session.mode())
                && "framework".equals(session.motionTarget())) {
            Path theme = themesDirectory.resolve(session.themeId()).normalize();
            requireInside(themesDirectory, theme, "目标主题路径越界");
            return theme;
        }
        Path module = moduleSourceDirectory.resolve(session.moduleId()).normalize();
        requireInside(moduleSourceDirectory, module, "目标模块路径越界");
        return module;
    }

    private String versionTargetKey(CodegenSession session) {
        return "motion".equals(session.mode())
                && "framework".equals(session.motionTarget())
                ? "theme:" + session.themeId()
                : "module:" + session.moduleId();
    }

    private void restoreFile(Path root, String sessionId, CodegenStep step) {
        Path file = root.resolve(step.path()).normalize();
        requireInside(root, file, "回滚路径越过目标目录");
        if (step.existedBefore()) {
            writeText(file, step.beforeContent());
            return;
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                throw new IllegalStateException("回滚目标不是普通文件：" + step.path());
            }
            moveToTrash(
                    file,
                    sessionId,
                    step.stepId() + "-" + file.getFileName()
            );
        }
    }

    private boolean liveContentMatches(Path file, String expected) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            return false;
        }
        try {
            return expected.equals(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return false;
        }
    }

    private void writeText(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("写入候选文本文件失败：" + file.getFileName());
        }
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取候选文本文件失败：" + file.getFileName());
        }
    }

    private void moveToTrash(Path source, String namespace, String name) {
        String safeNamespace = namespace.replaceAll("[^A-Za-z0-9_-]", "_");
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = trashDirectory
                .resolve(safeNamespace)
                .resolve(UUID.randomUUID() + "-" + safeName)
                .normalize();
        requireInside(trashDirectory, target, "代码生成回收站路径越界");
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException exception) {
            throw new IllegalStateException("移动到代码生成回收站失败：" + name);
        }
    }

    private void copyDirectoryIfPresent(Path source, Path destination) {
        if (source == null || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(destination);
            } catch (IOException exception) {
                throw new IllegalStateException("创建候选工作区失败", exception);
            }
            return;
        }
        copyDirectory(source, destination);
    }

    private void copyDirectory(Path source, Path destination) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new IllegalStateException("复制源必须是普通目录");
        }
        try {
            Files.createDirectories(destination);
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path path : paths.sorted().toList()) {
                    if (path.equals(source)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException("候选目录包含符号链接：" + path);
                    }
                    Path target = destination.resolve(source.relativize(path)).normalize();
                    requireInside(destination, target, "复制目标越过候选目录");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("复制候选目录失败", exception);
        }
    }

    static String hashTree(Path rootDirectory) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            Path buildOutput = rootDirectory.resolve("target").normalize();
            try (Stream<Path> paths = Files.walk(rootDirectory)) {
                paths.filter(path -> !path.equals(rootDirectory))
                        .filter(path -> !path.normalize().startsWith(buildOutput))
                        .sorted(Comparator.comparing(path ->
                                rootDirectory.relativize(path).toString()
                        ))
                        .forEach(path -> {
                            String relative = rootDirectory.relativize(path)
                                    .toString()
                                    .replace('\\', '/');
                            digest.update(relative.getBytes(StandardCharsets.UTF_8));
                            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && !Files.isSymbolicLink(path)) {
                                try {
                                    digest.update(Files.readAllBytes(path));
                                } catch (IOException exception) {
                                    throw new IllegalStateException(
                                            "读取版本摘要失败",
                                            exception
                                    );
                                }
                            }
                        });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("计算候选版本摘要失败", exception);
        }
    }

    private void reconcileVersions() {
        List<CodegenVersion> versions = new ArrayList<>(loadVersions());
        boolean changed = false;
        for (int index = 0; index < versions.size(); index++) {
            CodegenVersion version = versions.get(index);
            if (!"PENDING_RESTART".equals(version.status())) {
                continue;
            }
            Path target = repositoryRoot.resolve(version.relativePath()).normalize();
            requireInside(repositoryRoot, target, "版本目标路径越过仓库");
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && version.sourceHash().equals(hashTree(target))) {
                versions.set(index, version.withStatus("ACTIVE"));
                changed = true;
            }
        }
        Map<String, String> activeTargets = new LinkedHashMap<>();
        for (int index = 0; index < versions.size(); index++) {
            CodegenVersion version = versions.get(index);
            if ("ACTIVE".equals(version.status())) {
                String prior = activeTargets.putIfAbsent(
                        version.targetKey(),
                        version.versionId()
                );
                if (prior != null) {
                    versions.set(index, version.withStatus("DEPRECATED"));
                    changed = true;
                }
            }
        }
        if (changed) {
            saveVersions(versions);
        }
    }

    private void updateVersionStatus(String versionId, String status) {
        List<CodegenVersion> versions = new ArrayList<>(loadVersions());
        versions.replaceAll(version -> version.versionId().equals(versionId)
                ? version.withStatus(status)
                : version);
        saveVersions(versions);
    }

    private List<CodegenVersion> loadVersions() {
        if (!Files.isRegularFile(ledgerFile, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(ledgerFile.toFile(), VERSION_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块版本账本失败", exception);
        }
    }

    private void saveVersions(List<CodegenVersion> versions) {
        writeJsonAtomically(ledgerFile, versions);
    }

    private CodegenSession requireSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CODEGEN_SESSION_ID_INVALID",
                    "生成会话 ID 非法"
            );
        }
        Path file = sessionsDirectory.resolve(sessionId + ".json").normalize();
        requireInside(sessionsDirectory, file, "生成会话路径越界");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CODEGEN_SESSION_NOT_FOUND",
                    "生成会话不存在"
            );
        }
        return readSession(file);
    }

    private CodegenSession readSession(Path file) {
        try {
            CodegenSession session = objectMapper.readValue(file.toFile(), CodegenSession.class);
            if (session.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException("不支持的生成会话版本");
            }
            return session;
        } catch (IOException exception) {
            throw new IllegalStateException("读取生成会话失败", exception);
        }
    }

    private void saveSession(CodegenSession session) {
        Path file = sessionsDirectory.resolve(session.sessionId() + ".json").normalize();
        requireInside(sessionsDirectory, file, "生成会话路径越界");
        writeJsonAtomically(file, session);
    }

    private void writeJsonAtomically(Path target, Object value) {
        Path temporary = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp"
        );
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), value);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            try {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(temporary.toFile(), value);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException nested) {
                throw new IllegalStateException("写入生成数据失败", nested);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入生成数据失败", exception);
        }
    }

    private SessionView view(CodegenSession session) {
        List<StepView> steps = session.steps().stream()
                .map(StepView::from)
                .toList();
        List<CodegenVersion> history = loadVersions().stream()
                .filter(version -> version.targetKey().equals(versionTargetKey(session)))
                .sorted(Comparator.comparing(CodegenVersion::createdAt).reversed())
                .toList();
        return new SessionView(
                session.sessionId(),
                session.mode(),
                session.motionTarget(),
                session.themeId(),
                session.moduleId(),
                session.summary(),
                session.status(),
                session.build().status(),
                session.build().message(),
                session.preview().status(),
                session.preview().url(),
                session.applyVersionId(),
                steps,
                session.interfaces(),
                history,
                session.createdAt(),
                session.updatedAt()
        );
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private void initializeDirectories() {
        try {
            for (Path directory : List.of(
                    root,
                    sessionsDirectory,
                    workspacesDirectory,
                    buildsDirectory,
                    artifactsDirectory,
                    versionsDirectory,
                    trashDirectory
            )) {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(directory)) {
                    throw new IllegalStateException("生成数据目录必须是普通本地目录：" + directory);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("初始化代码生成数据目录失败", exception);
        }
    }

    private void resetStalePreviews() {
        try (Stream<Path> files = Files.list(sessionsDirectory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .toList()) {
                CodegenSession session = readSession(file);
                if (!"STOPPED".equals(session.preview().status())) {
                    saveSession(session.withPreview(new PreviewState("STOPPED", "", "")));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理过期代码生成预览状态失败", exception);
        }
    }

    private Path commonProjectRoot(String moduleDirectory, String themeDirectory) {
        Path modules = Path.of(moduleDirectory).toAbsolutePath().normalize();
        Path themes = Path.of(themeDirectory).toAbsolutePath().normalize();
        Path ancestor = modules;
        while (ancestor != null) {
            if (Files.isRegularFile(
                    ancestor.resolve("pom.xml"),
                    LinkOption.NOFOLLOW_LINKS
            )) {
                return ancestor;
            }
            ancestor = ancestor.getParent();
        }
        Path cursor = modules;
        while (cursor != null && !filesEquals(cursor, themes.getParent())) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("无法确定 Module 与 themes 的公共仓库根目录");
        }
        return cursor;
    }

    private boolean filesEquals(Path left, Path right) {
        return left != null
                && right != null
                && left.toAbsolutePath().normalize()
                .equals(right.toAbsolutePath().normalize());
    }

    private String repositoryRelative(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        requireInside(repositoryRoot, absolute, "版本目标不在仓库内");
        return repositoryRoot.relativize(absolute).toString().replace('\\', '/');
    }

    private void requireInside(Path rootPath, Path candidate, String message) {
        if (candidate == null
                || !candidate.toAbsolutePath().normalize()
                .startsWith(rootPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(message);
        }
    }

    private String safeProposalPath(String value) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isBlank()
                || path.startsWith("/")
                || path.contains(":")
                || path.contains("\u0000")) {
            throw invalidProposal("模型返回了非法文件路径");
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalidProposal("模型返回了未规范化的文件路径：" + path);
            }
        }
        return path;
    }

    private String normalizeProposalPath(String mode, Target target, String path) {
        if (!"motion".equals(mode) || !"framework".equals(target.motionTarget())) {
            return path;
        }
        Set<String> themeFiles = Set.of("motion.css", "tokens.css");
        for (String prefix : List.of(
                "themes/" + target.themeId() + "/",
                target.themeId() + "/"
        )) {
            if (path.startsWith(prefix) && themeFiles.contains(path.substring(prefix.length()))) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    private String boundedTitle(String value, String fallback) {
        String title = value == null || value.isBlank() ? fallback : value.trim();
        return title.length() <= 200 ? title : title.substring(0, 200);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String tail(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(value.length() - maximum);
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "候选构建失败"
                : tail(message, 1800);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "CODEGEN_INVALID_REQUEST", message);
    }

    private ApiException invalidProposal(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CODEGEN_INVALID_PROPOSAL", message);
    }

    /** Stop preview hosts and worker threads owned by this service. */
    @PreDestroy
    public void shutdown() {
        previewService.stopAll();
        workers.shutdownNow();
    }

    private record Target(
            String moduleId,
            String motionTarget,
            String themeId,
            Path sourceRoot
    ) {
    }

    record ParentCoordinates(String groupId, String artifactId, String version) {
    }

    /** Generate request assembled by the module UI. */
    public record GenerateRequest(
            String mode,
            String prompt,
            String targetModuleId,
            String motionTarget,
            String themeId,
            List<Map<String, Object>> moduleCatalog,
            List<Map<String, Object>> motionScopes,
            List<Map<String, Object>> motionNodes,
            List<Map<String, Object>> motionAnnotations
    ) {
        public GenerateRequest {
            moduleCatalog = moduleCatalog == null ? List.of() : List.copyOf(moduleCatalog);
            motionScopes = motionScopes == null ? List.of() : List.copyOf(motionScopes);
            motionNodes = motionNodes == null ? List.of() : List.copyOf(motionNodes);
            motionAnnotations = motionAnnotations == null
                    ? List.of()
                    : List.copyOf(motionAnnotations);
        }
    }

    /** Persisted generation session. */
    public record CodegenSession(
            int schemaVersion,
            String sessionId,
            String mode,
            String motionTarget,
            String themeId,
            String targetModuleId,
            String moduleId,
            String prompt,
            String summary,
            String status,
            List<CodegenStep> steps,
            Map<String, Object> interfaces,
            BuildState build,
            PreviewState preview,
            String applyVersionId,
            String reuseNote,
            String createdAt,
            String updatedAt
    ) {
        public CodegenSession {
            steps = List.copyOf(steps == null ? List.of() : steps);
            interfaces = Map.copyOf(interfaces == null ? Map.of() : interfaces);
        }

        private CodegenSession withBuild(BuildState nextBuild, String timestamp) {
            return new CodegenSession(
                    schemaVersion,
                    sessionId,
                    mode,
                    motionTarget,
                    themeId,
                    targetModuleId,
                    moduleId,
                    prompt,
                    summary,
                    status,
                    steps,
                    interfaces,
                    nextBuild,
                    preview,
                    applyVersionId,
                    reuseNote,
                    createdAt,
                    timestamp
            );
        }

        private CodegenSession withPreview(PreviewState nextPreview) {
            return new CodegenSession(
                    schemaVersion,
                    sessionId,
                    mode,
                    motionTarget,
                    themeId,
                    targetModuleId,
                    moduleId,
                    prompt,
                    summary,
                    status,
                    steps,
                    interfaces,
                    build,
                    nextPreview,
                    applyVersionId,
                    reuseNote,
                    createdAt,
                    Instant.now().toString()
            );
        }

        private CodegenSession withApplied(
                String nextStatus,
                String versionId,
                String timestamp
        ) {
            return new CodegenSession(
                    schemaVersion,
                    sessionId,
                    mode,
                    motionTarget,
                    themeId,
                    targetModuleId,
                    moduleId,
                    prompt,
                    summary,
                    nextStatus,
                    steps,
                    interfaces,
                    build,
                    new PreviewState("STOPPED", "", ""),
                    versionId,
                    reuseNote,
                    createdAt,
                    timestamp
            );
        }

        private CodegenSession withRollback(
                List<CodegenStep> nextSteps,
                String nextStatus,
                String buildStatus,
                String timestamp
        ) {
            return new CodegenSession(
                    schemaVersion,
                    sessionId,
                    mode,
                    motionTarget,
                    themeId,
                    targetModuleId,
                    moduleId,
                    prompt,
                    summary,
                    nextStatus,
                    List.copyOf(nextSteps),
                    interfaces,
                    new BuildState(
                            buildStatus,
                            "步骤回滚后需要重新构建",
                            "",
                            "",
                            ""
                    ),
                    new PreviewState("STOPPED", "", ""),
                    applyVersionId,
                    reuseNote,
                    createdAt,
                    timestamp
            );
        }
    }

    /** One generated file operation; later steps can be rolled back as a cascade. */
    public record CodegenStep(
            String stepId,
            int index,
            String title,
            String path,
            String status,
            boolean existedBefore,
            String beforeContent,
            String afterContent
    ) {
        private CodegenStep withStatus(String nextStatus) {
            return new CodegenStep(
                    stepId,
                    index,
                    title,
                    path,
                    nextStatus,
                    existedBefore,
                    beforeContent,
                    afterContent
            );
        }

        private CodegenStep withAfterContent(String nextAfterContent) {
            return new CodegenStep(
                    stepId,
                    index,
                    title,
                    path,
                    status,
                    existedBefore,
                    beforeContent,
                    nextAfterContent
            );
        }
    }

    /** Candidate build state. */
    public record BuildState(
            String status,
            String message,
            String jarPath,
            String startedAt,
            String finishedAt
    ) {
        public BuildState {
            status = status == null ? "PENDING" : status;
            message = message == null ? "" : message;
            jarPath = jarPath == null ? "" : jarPath;
            startedAt = startedAt == null ? "" : startedAt;
            finishedAt = finishedAt == null ? "" : finishedAt;
        }
    }

    /** Candidate mirror state. */
    public record PreviewState(String status, String url, String startedAt) {
        public PreviewState {
            status = status == null ? "STOPPED" : status;
            url = url == null ? "" : url;
            startedAt = startedAt == null ? "" : startedAt;
        }
    }

    /** Applied source version recognized by the framework ledger. */
    public record CodegenVersion(
            String versionId,
            String targetKey,
            String moduleId,
            String mode,
            String status,
            String sessionId,
            String sourceHash,
            String createdAt,
            String relativePath
    ) {
        private CodegenVersion withStatus(String nextStatus) {
            return new CodegenVersion(
                    versionId,
                    targetKey,
                    moduleId,
                    mode,
                    nextStatus,
                    sessionId,
                    sourceHash,
                    createdAt,
                    relativePath
            );
        }
    }

    /** Session list item without file bodies. */
    public record SessionSummary(
            String sessionId,
            String mode,
            String moduleId,
            String status,
            String buildStatus,
            String previewStatus,
            int stepCount,
            String createdAt,
            String updatedAt
    ) {
        private static SessionSummary from(CodegenSession session) {
            return new SessionSummary(
                    session.sessionId(),
                    session.mode(),
                    session.moduleId(),
                    session.status(),
                    session.build().status(),
                    session.preview().status(),
                    session.steps().size(),
                    session.createdAt(),
                    session.updatedAt()
            );
        }
    }

    /** Safe step projection used by the module UI. */
    public record StepView(
            String stepId,
            int index,
            String title,
            String path,
            String status,
            boolean existedBefore,
            int bytes
    ) {
        private static StepView from(CodegenStep step) {
            return new StepView(
                    step.stepId(),
                    step.index(),
                    step.title(),
                    step.path(),
                    step.status(),
                    step.existedBefore(),
                    step.afterContent().getBytes(StandardCharsets.UTF_8).length
            );
        }
    }

    /** Full session view returned to the frontend. */
    public record SessionView(
            String sessionId,
            String mode,
            String motionTarget,
            String themeId,
            String moduleId,
            String summary,
            String status,
            String buildStatus,
            String buildMessage,
            String previewStatus,
            String previewUrl,
            String applyVersionId,
            List<StepView> steps,
            Map<String, Object> interfaces,
            List<CodegenVersion> versions,
            String createdAt,
            String updatedAt
    ) {
        public SessionView {
            steps = List.copyOf(steps == null ? List.of() : steps);
            interfaces = Map.copyOf(interfaces == null ? Map.of() : interfaces);
            versions = List.copyOf(versions == null ? List.of() : versions);
        }
    }

    /** Explicit diff content; only fetched for one selected step. */
    public record StepDiff(
            String stepId,
            String path,
            boolean existedBefore,
            String beforeContent,
            String afterContent,
            String status
    ) {
    }
}
