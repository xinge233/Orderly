package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModulePackageInstaller.ModulePackageException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 从固定 GitHub 分发分支读取并安装模块源码包。
 *
 * <p>客户端只提交模块 ID。目录和源码包地址由宿主配置推导，HTTP 只接受 HTTPS，
 * 目录、包大小和摘要全部在宿主校验。安装任务会等待后台构建器把新模块追加为
 * {@code READY}；未启用后台构建时只返回需要重启的真实状态。</p>
 */
@Service
public class ModuleDistributionService {

    private static final int CATALOG_SCHEMA_VERSION = 1;
    private static final int CATALOG_API_VERSION = 1;
    private static final String MODULE_ID_PATTERN = "^module_[a-z0-9_]+$";
    private static final String OFFICIAL_REPOSITORY_PREFIX =
            "/xinge233/Orderly/module-distribution/";
    private static final String OFFICIAL_CATALOG_PATH =
            OFFICIAL_REPOSITORY_PREFIX + "catalog.json";
    private static final long CATALOG_CACHE_MILLIS = 10_000L;
    private static final int MAX_RETAINED_JOBS = 200;
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "INSTALLED",
            "RESTART_REQUIRED",
            "FAILED",
            "BUILD_FAILED",
            "BUILD_TIMEOUT",
            "MODULE_INSTALL_FAILED",
            "MODULE_INSTALL_INTERRUPTED",
            "PACKAGE_SIZE_MISMATCH",
            "PACKAGE_HASH_MISMATCH",
            "PACKAGE_EXTRACTION_FAILED",
            "PACKAGE_TOO_MANY_FILES",
            "PACKAGE_ENTRY_TOO_LARGE",
            "PACKAGE_COMPRESSION_RATIO",
            "PACKAGE_TOO_LARGE_EXPANDED",
            "INVALID_PACKAGE_PATH",
            "INVALID_PACKAGE_ROOT",
            "FORBIDDEN_PACKAGE_FILE",
            "DUPLICATE_PACKAGE_ENTRY",
            "MISSING_MODULE_FILE",
            "INVALID_MODULE_PACKAGE",
            "MODULE_ID_MISMATCH",
            "SYSTEM_MODULE_FORBIDDEN",
            "INVALID_MODULE_UI",
            "INVALID_MODULE_SERVICE",
            "MODULE_CONFLICT",
            "MODULE_STAGE_COMMIT_FAILED",
            "INVALID_MODULE_PATH",
            "INVALID_MODULE_ID"
    );
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "schemaVersion", "apiVersion", "generatedAt", "sourceCommit", "modules"
    );
    private static final Set<String> CATALOG_MODULE_FIELDS = Set.of(
            "moduleId", "title", "description", "version",
            "packagePath", "sha256", "size"
    );

    private final ContractCatalog catalog;
    private final ModuleLifecycleService lifecycle;
    private final ModulePackageInstaller installer;
    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final Path moduleSourceDirectory;
    private final Path dataDirectory;
    private final URI catalogUri;
    private final long maxCatalogBytes;
    private final long maxPackageBytes;
    private final long installTimeoutMillis;
    private final boolean backgroundBuildEnabled;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Map<String, InstallJobState> jobs = new ConcurrentHashMap<>();
    private final Set<String> activeModuleIds = ConcurrentHashMap.newKeySet();

    private volatile CachedCatalog cachedCatalog;

    public ModuleDistributionService(
            ContractCatalog catalog,
            ModuleLifecycleService lifecycle,
            ModulePackageInstaller installer,
            ObjectMapper objectMapper,
            ExecutionJournal journal,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.data-dir:.orderly-data}") String dataDirectory,
            @Value("${orderly.module-distribution.catalog-url:https://raw.githubusercontent.com/xinge233/Orderly/module-distribution/catalog.json}")
            String catalogUrl,
            @Value("${orderly.module-distribution.max-catalog-bytes:262144}") long maxCatalogBytes,
            @Value("${orderly.module-distribution.max-package-bytes:20971520}") long maxPackageBytes,
            @Value("${orderly.module-distribution.install-timeout-seconds:600}") long installTimeoutSeconds,
            @Value("${orderly.background-modules-enabled:false}") boolean backgroundBuildEnabled
    ) {
        this.catalog = catalog;
        this.lifecycle = lifecycle;
        this.installer = installer;
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.catalogUri = requireHttpsCatalogUri(catalogUrl);
        this.maxCatalogBytes = maxCatalogBytes;
        this.maxPackageBytes = maxPackageBytes;
        this.installTimeoutMillis = Duration.ofSeconds(installTimeoutSeconds).toMillis();
        this.backgroundBuildEnabled = backgroundBuildEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 读取并验证远端目录，附带本机安装和构建状态。 */
    public CatalogView catalog() {
        try {
            RemoteCatalog remote = loadCatalog(false);
            return enrich(remote);
        } catch (ModuleDistributionException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    exception.code(),
                    exception.getMessage()
            );
        }
    }

    /**
     * 创建异步安装任务。下载和构建在后台执行，接口只返回可轮询的任务。
     */
    public InstallAccepted install(String moduleId) {
        String normalizedId;
        try {
            normalizedId = requireBusinessModuleId(moduleId);
        } catch (ModuleDistributionException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    exception.code(),
                    exception.getMessage()
            );
        }
        if (catalog.findModule(normalizedId).isPresent()
                || Files.exists(moduleSourceDirectory.resolve(normalizedId), LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_ALREADY_EXISTS",
                    "模块已存在，当前版本不支持覆盖更新：" + normalizedId
            );
        }
        if (!activeModuleIds.add(normalizedId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_INSTALL_IN_PROGRESS",
                    "该模块已有安装任务正在运行"
            );
        }
        try {
            RemoteCatalog remote = loadCatalog(true);
            RemoteModule module = remote.modules().stream()
                    .filter(item -> item.moduleId().equals(normalizedId))
                    .findFirst()
                    .orElseThrow(() -> new ModuleDistributionException(
                            "MODULE_NOT_IN_CATALOG",
                            "分发目录中不存在模块：" + normalizedId
                    ));
            InstallJobState state = new InstallJobState(
                    UUID.randomUUID().toString(),
                    normalizedId,
                    module.title(),
                    module.version()
            );
            pruneCompletedJobs();
            jobs.put(state.jobId, state);
            executor.submit(() -> performInstall(state, module));
            return new InstallAccepted(state.jobId, state.moduleId, state.status);
        } catch (ModuleDistributionException exception) {
            activeModuleIds.remove(normalizedId);
            throw new ApiException(
                    exception.code().equals("MODULE_NOT_IN_CATALOG")
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.BAD_GATEWAY,
                    exception.code(),
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            activeModuleIds.remove(normalizedId);
            throw exception;
        }
    }

    /** 返回安装任务快照。 */
    public InstallStatus status(String jobId) {
        InstallJobState state = jobs.get(jobId);
        if (state == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "MODULE_INSTALL_NOT_FOUND",
                    "模块安装任务不存在"
            );
        }
        return state.view();
    }

    private void performInstall(InstallJobState state, RemoteModule module) {
        Path stagingRoot = null;
        try {
            state.update("DOWNLOADING", "正在下载模块包");
            byte[] packageBytes = fetchBytes(resolvePackageUri(module.packagePath()), maxPackageBytes);
            state.update("VERIFYING", "正在校验模块包");
            stagingRoot = dataDirectory
                    .resolve("module-downloads")
                    .resolve(state.jobId)
                    .normalize();
            Path staged = installer.stage(
                    packageBytes,
                    module.moduleId(),
                    module.sha256(),
                    module.size(),
                    stagingRoot
            );
            state.update("STAGING", "正在提交模块源码");
            Path committed = installer.commit(staged, moduleSourceDirectory);
            installer.cleanup(stagingRoot);
            stagingRoot = null;
            state.update("BUILDING", "正在等待本地构建");
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    null,
                    "install-module",
                    "STAGED",
                    "已安装模块源码：" + module.moduleId() + " -> " + committed
            );

            if (!backgroundBuildEnabled) {
                state.update("RESTART_REQUIRED", "模块源码已下载，当前启动模式需要重启后生效");
                journal.append(
                        "MODULE_CONTROL",
                        "MODULE_CONTROL",
                        null,
                        null,
                        null,
                        "install-module",
                        "RESTART_REQUIRED",
                        "模块源码已下载，当前未启用后台构建：" + module.moduleId()
                );
                return;
            }

            ModuleLifecycleService.Entry build = waitForModule(module.moduleId());
            state.update("INSTALLED", build.message());
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    null,
                    "install-module",
                    "INSTALLED",
                    "模块已构建并追加：" + module.moduleId()
            );
        } catch (ModuleDistributionException exception) {
            fail(state, exception.code(), exception.getMessage());
        } catch (ModulePackageException exception) {
            fail(state, exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            fail(state, "MODULE_INSTALL_FAILED", safeMessage(exception));
        } finally {
            installer.cleanup(stagingRoot);
            activeModuleIds.remove(state.moduleId);
        }
    }

    private ModuleLifecycleService.Entry waitForModule(String moduleId) {
        long deadline = System.nanoTime() + Duration.ofMillis(installTimeoutMillis).toNanos();
        String lastMessage = "模块源码已提交，等待后台构建";
        while (System.nanoTime() < deadline) {
            ModuleLifecycleService.Snapshot snapshot = lifecycle.snapshot();
            ModuleLifecycleService.Entry entry = snapshot.modules().stream()
                    .filter(item -> moduleId.equals(item.sourceName()))
                    .findFirst()
                    .orElse(null);
            if (entry != null) {
                lastMessage = entry.message();
                if ("READY".equals(entry.status())) {
                    return entry;
                }
                if ("FAILED".equals(entry.status())) {
                    throw new ModuleDistributionException("BUILD_FAILED", entry.message());
                }
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ModuleDistributionException(
                        "MODULE_INSTALL_INTERRUPTED",
                        "模块安装等待被中断"
                );
            }
        }
        throw new ModuleDistributionException("BUILD_TIMEOUT", lastMessage);
    }

    private void fail(InstallJobState state, String code, String message) {
        state.finish(code, message);
        journal.append(
                "MODULE_CONTROL",
                "MODULE_CONTROL",
                null,
                null,
                null,
                "install-module",
                "FAILED",
                "模块安装失败：" + state.moduleId + "；" + code + "；" + message
        );
    }

    private CatalogView enrich(RemoteCatalog remote) {
        Map<String, ModuleLifecycleService.Entry> builds = lifecycle.snapshot().modules().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ModuleLifecycleService.Entry::sourceName,
                        item -> item,
                        (left, right) -> left
                ));
        List<CatalogItem> items = remote.modules().stream()
                .map(module -> {
                    ModuleLifecycleService.Entry build = builds.get(module.moduleId());
                    boolean loaded = catalog.findModule(module.moduleId()).isPresent();
                    boolean sourcePresent = Files.isDirectory(
                            moduleSourceDirectory.resolve(module.moduleId()),
                            LinkOption.NOFOLLOW_LINKS
                    );
                    String status = loaded
                            ? "INSTALLED"
                            : build != null
                                ? build.status()
                                : sourcePresent ? "SOURCE_PRESENT" : "AVAILABLE";
                    ImportInstallStatus install = currentInstall(module.moduleId());
                    if (install != null && !"INSTALLED".equals(install.status)) {
                        status = install.status;
                    }
                    String message = install != null
                            ? install.message
                            : build != null ? build.message() : "";
                    boolean canInstall = !loaded
                            && !sourcePresent
                            && (install == null || !install.active);
                    boolean canRetryBuild = sourcePresent
                            && (build == null || "FAILED".equals(build.status()));
                    return new CatalogItem(
                            module.moduleId(),
                            module.title(),
                            module.description(),
                            module.version(),
                            module.size(),
                            status,
                            message,
                            canInstall,
                            install == null ? null : install.jobId,
                            install != null && install.active,
                            canRetryBuild
                    );
                })
                .toList();
        return new CatalogView(
                remote.schemaVersion(),
                remote.apiVersion(),
                remote.generatedAt(),
                remote.sourceCommit(),
                items
        );
    }

    private ImportInstallStatus currentInstall(String moduleId) {
        return jobs.values().stream()
                .filter(job -> moduleId.equals(job.moduleId))
                .max(Comparator.comparing(job -> job.updatedAt))
                .map(job -> new ImportInstallStatus(
                        job.jobId,
                        job.status,
                        job.message,
                        job.active
                ))
                .orElse(null);
    }

    private RemoteCatalog loadCatalog(boolean force) {
        CachedCatalog cache = cachedCatalog;
        long now = System.currentTimeMillis();
        if (!force && cache != null && now - cache.loadedAtMillis < CATALOG_CACHE_MILLIS) {
            return cache.catalog;
        }
        byte[] bytes = fetchBytes(catalogUri, maxCatalogBytes);
        RemoteCatalog parsed = parseCatalog(bytes);
        cachedCatalog = new CachedCatalog(parsed, now);
        return parsed;
    }

    RemoteCatalog parseCatalog(byte[] bytes) {
        try {
            JsonParser parser = objectMapper.getFactory().createParser(bytes);
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null
                    || root.path("schemaVersion").asInt(-1) != CATALOG_SCHEMA_VERSION
                    || root.path("apiVersion").asInt(-1) != CATALOG_API_VERSION
                    || !root.path("modules").isArray()) {
                throw new ModuleDistributionException(
                        "INVALID_MODULE_CATALOG",
                        "模块分发目录格式或版本不受支持"
                );
            }
            rejectUnknownFields(root, CATALOG_FIELDS, "模块分发目录");
            String generatedAt = requireText(root, "generatedAt");
            try {
                Instant.parse(generatedAt);
            } catch (RuntimeException exception) {
                throw new ModuleDistributionException(
                        "INVALID_MODULE_CATALOG",
                        "模块分发目录 generatedAt 不是有效时间"
                );
            }
            String sourceCommit = optionalText(root, "sourceCommit");
            if (sourceCommit != null && !sourceCommit.matches("^[a-f0-9]{7,40}$")) {
                throw new ModuleDistributionException(
                        "INVALID_MODULE_CATALOG",
                        "模块分发目录 sourceCommit 格式无效"
                );
            }
            List<RemoteModule> modules = new ArrayList<>();
            Set<String> moduleIds = new HashSet<>();
            for (JsonNode node : root.path("modules")) {
                rejectUnknownFields(node, CATALOG_MODULE_FIELDS, "模块分发条目");
                String moduleId = requireBusinessModuleId(requireText(node, "moduleId"));
                if (!moduleIds.add(moduleId)) {
                    throw new ModuleDistributionException(
                            "DUPLICATE_MODULE_CATALOG_ENTRY",
                            "模块分发目录包含重复模块：" + moduleId
                    );
                }
                String packagePath = requireText(node, "packagePath");
                requireRelativePackagePath(packagePath);
                String sha256 = requireText(node, "sha256").toLowerCase(Locale.ROOT);
                if (!sha256.matches("^[a-f0-9]{64}$")) {
                    throw new ModuleDistributionException(
                            "INVALID_PACKAGE_HASH",
                            "模块包摘要格式无效：" + moduleId
                    );
                }
                long size = node.path("size").asLong(-1);
                if (size <= 0 || size > maxPackageBytes) {
                    throw new ModuleDistributionException(
                            "INVALID_PACKAGE_SIZE",
                            "模块包大小无效：" + moduleId
                    );
                }
                modules.add(new RemoteModule(
                        moduleId,
                        requireText(node, "title"),
                        requireText(node, "description"),
                        requireText(node, "version"),
                        packagePath,
                        sha256,
                        size
                ));
            }
            return new RemoteCatalog(
                    CATALOG_SCHEMA_VERSION,
                    CATALOG_API_VERSION,
                    generatedAt,
                    sourceCommit,
                    List.copyOf(modules)
            );
        } catch (ModuleDistributionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ModuleDistributionException(
                    "INVALID_MODULE_CATALOG",
                    "模块分发目录无法解析"
            );
        }
    }

    private URI resolvePackageUri(String packagePath) {
        requireRelativePackagePath(packagePath);
        URI resolved = catalogUri.resolve(packagePath).normalize();
        if (!"https".equalsIgnoreCase(resolved.getScheme())
                || !catalogUri.getHost().equalsIgnoreCase(resolved.getHost())
                || !resolved.getPath().startsWith(OFFICIAL_REPOSITORY_PREFIX + "packages/")) {
            throw new ModuleDistributionException(
                    "INVALID_PACKAGE_URL",
                    "模块包地址必须位于固定 HTTPS 分发主机"
            );
        }
        return resolved;
    }

    private byte[] fetchBytes(URI uri, long limit) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/zip, application/octet-stream")
                .header("User-Agent", "Orderly-Module-Distribution/1")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // 响应体不进入 UI；状态码已经足以区分远端不可用。
                }
                throw new ModuleDistributionException(
                        "MODULE_DISTRIBUTION_UNAVAILABLE",
                        "模块分发服务返回 HTTP " + response.statusCode()
                );
            }
            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (contentLength > limit) {
                throw new ModuleDistributionException(
                        "MODULE_DISTRIBUTION_TOO_LARGE",
                        "模块分发响应超过大小限制"
                );
            }
            try (InputStream input = response.body();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new ModuleDistributionException(
                                "MODULE_DISTRIBUTION_TOO_LARGE",
                                "模块分发响应超过大小限制"
                        );
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (ModuleDistributionException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModuleDistributionException(
                    "MODULE_DISTRIBUTION_INTERRUPTED",
                    "模块分发请求被中断"
            );
        } catch (IOException exception) {
            throw new ModuleDistributionException(
                    "MODULE_DISTRIBUTION_UNAVAILABLE",
                    "无法读取模块分发内容：" + exception.getMessage()
            );
        }
    }

    private URI requireHttpsCatalogUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("模块分发目录 URL 非法", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !uri.getHost().equalsIgnoreCase("raw.githubusercontent.com")
                || !OFFICIAL_CATALOG_PATH.equals(uri.getPath())) {
            throw new IllegalArgumentException(
                    "模块分发目录只允许 xinge233/Orderly/module-distribution/catalog.json"
            );
        }
        return uri;
    }

    private String requireBusinessModuleId(String moduleId) {
        if (moduleId == null
                || !moduleId.matches(MODULE_ID_PATTERN)
                || moduleId.startsWith("module_system_")) {
            throw new ModuleDistributionException(
                    "INVALID_MODULE_ID",
                    "模块 ID 非法或属于受保护系统模块"
            );
        }
        return moduleId;
    }

    private void requireRelativePackagePath(String packagePath) {
        if (packagePath == null
                || packagePath.isBlank()
                || packagePath.startsWith("/")
                || packagePath.contains("\\")
                || !packagePath.startsWith("packages/")
                || !packagePath.endsWith(".zip")
                || packagePath.contains("..")) {
            throw new ModuleDistributionException(
                    "INVALID_PACKAGE_PATH",
                    "模块包路径必须是 packages/ 下的安全相对 ZIP 路径"
            );
        }
    }

    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new ModuleDistributionException(
                    "INVALID_MODULE_CATALOG",
                    "模块分发目录缺少字段：" + field
            );
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String description) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new ModuleDistributionException(
                        "INVALID_MODULE_CATALOG",
                        description + "包含未知字段：" + field
                );
            }
        });
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "模块安装失败" : message;
    }

    private void pruneCompletedJobs() {
        if (jobs.size() < MAX_RETAINED_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(job -> !job.active)
                .sorted(Comparator.comparing(job -> job.updatedAt))
                .limit(Math.max(1, jobs.size() - MAX_RETAINED_JOBS + 1))
                .forEach(job -> jobs.remove(job.jobId, job));
    }

    @PreDestroy
    public void close() {
        executor.close();
    }

    /** 安装任务状态。 */
    public record InstallStatus(
            String jobId,
            String moduleId,
            String title,
            String version,
            String status,
            String message,
            String updatedAt,
            boolean terminal
    ) {
    }

    /** 创建安装任务后的响应。 */
    public record InstallAccepted(String jobId, String moduleId, String status) {
    }

    /** 分发目录中的单项模块及本机状态。 */
    public record CatalogItem(
            String moduleId,
            String title,
            String description,
            String version,
            long size,
            String status,
            String message,
            boolean canInstall,
            String jobId,
            boolean jobActive,
            boolean canRetryBuild
    ) {
    }

    /** 供前端展示的已验证分发目录。 */
    public record CatalogView(
            int schemaVersion,
            int apiVersion,
            String generatedAt,
            String sourceCommit,
            List<CatalogItem> items
    ) {
        public CatalogView {
            items = List.copyOf(items);
        }
    }

    record RemoteCatalog(
            int schemaVersion,
            int apiVersion,
            String generatedAt,
            String sourceCommit,
            List<RemoteModule> modules
    ) {
    }

    record RemoteModule(
            String moduleId,
            String title,
            String description,
            String version,
            String packagePath,
            String sha256,
            long size
    ) {
    }

    private record CachedCatalog(RemoteCatalog catalog, long loadedAtMillis) {
    }

    private record ImportInstallStatus(
            String jobId,
            String status,
            String message,
            boolean active
    ) {
    }

    private static final class InstallJobState {
        private final String jobId;
        private final String moduleId;
        private final String title;
        private final String version;
        private volatile String status = "QUEUED";
        private volatile String message = "等待安装";
        private volatile String updatedAt = Instant.now().toString();
        private volatile String completedAt = "";
        private volatile boolean active = true;

        private InstallJobState(String jobId, String moduleId, String title, String version) {
            this.jobId = jobId;
            this.moduleId = moduleId;
            this.title = title;
            this.version = version;
        }

        private void update(String nextStatus, String nextMessage) {
            status = nextStatus;
            message = nextMessage == null ? "" : nextMessage;
            updatedAt = Instant.now().toString();
            active = !TERMINAL_STATUSES.contains(nextStatus);
            if (!active) {
                completedAt = updatedAt;
            }
        }

        private void finish(String nextStatus, String nextMessage) {
            status = nextStatus;
            message = nextMessage == null ? "" : nextMessage;
            updatedAt = Instant.now().toString();
            completedAt = updatedAt;
            active = false;
        }

        private InstallStatus view() {
            return new InstallStatus(
                    jobId,
                    moduleId,
                    title,
                    version,
                    status,
                    message,
                    updatedAt,
                    !active
            );
        }
    }

    private static class ModuleDistributionException extends RuntimeException {
        private final String code;

        private ModuleDistributionException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
