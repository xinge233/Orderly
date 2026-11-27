package com.orderly.host.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.AuthorizedFileRoot;
import com.orderly.kernel.FileEntry;
import com.orderly.kernel.FileReadResult;
import com.orderly.kernel.FileWriteResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.TrashEntry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 归序工作区与用户显式授权文件根上的本地模块运行时。
 *
 * <p>工作区、文件根注册、集合状态和默认回收站位于配置的应用数据目录下；授权根对象
 * 删除时使用同卷项目回收站。模块通过该实现执行受限路径解析、文件写入、回收站移动和
 * 集合持久化；框架不向模块公开物理删除能力或文件根绝对路径。路径解析会拒绝绝对路径、
 * 词法越界、系统保护目录和经过现有链接或重解析点的逃逸，但外部模块 JAR 本身仍是本地
 * 受信任代码，而不是恶意代码安全沙箱。</p>
 */
@Component
public class WorkspaceRuntime implements ModuleRuntime {

    private static final TypeReference<List<Map<String, Object>>> ITEMS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<FileRootRegistration>> FILE_ROOTS_TYPE =
            new TypeReference<>() {
            };
    private static final int MAX_READ_CHARS = 20_000;
    private static final long MAX_TEXT_FILE_BYTES = 16L * 1024L * 1024L;
    private static final String PER_VOLUME_TRASH_DIRECTORY = ".orderly-trash";
    private static final List<String> PROTECTED_WINDOWS_DIRECTORIES = List.of(
            "windows",
            "program files",
            "program files (x86)",
            "programdata",
            "system volume information",
            "$recycle.bin",
            "recovery",
            "boot",
            "perflogs"
    );
    private static final List<String> PROTECTED_UNIX_DIRECTORIES = List.of(
            "proc",
            "sys",
            "dev",
            "etc",
            "bin",
            "sbin",
            "boot",
            "usr"
    );

    private final Path dataRoot;
    private final Path workspaceRoot;
    private final Path trashRoot;
    private final Path stateRoot;
    private final Path fileRootsFile;
    private final ObjectMapper objectMapper;
    private Path workspaceReal;
    private List<FileRootRegistration> fileRoots = List.of();
    private final Map<String, ReadGrant> readGrants = new ConcurrentHashMap<>();

    /**
     * 计算应用数据、工作区、回收站和集合状态的规范化路径。
     *
     * <p>构造过程不创建目录，也不读取业务状态。相对 {@code dataDirectory} 以宿主
     * 进程工作目录为基准；后续文件操作只有在 {@link #initialize()} 成功后才有完整
     * 的真实路径边界信息。</p>
     */
    public WorkspaceRuntime(
            @Value("${orderly.data-dir:.orderly-data}") String dataDirectory,
            ObjectMapper objectMapper
    ) {
        this.dataRoot = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.workspaceRoot = dataRoot.resolve("workspace").normalize();
        this.trashRoot = dataRoot.resolve("trash").normalize();
        this.stateRoot = dataRoot.resolve("state").normalize();
        this.fileRootsFile = dataRoot.resolve("file-roots.json");
        this.objectMapper = objectMapper;
    }

    /**
     * 创建应用自有目录并记录工作区真实根路径。
     *
     * <p>该方法在 Spring 启动阶段执行，可重复调用且不会清空已有数据；只补齐缺失目录。
     * 目录创建或真实路径解析失败时抛出运行时异常并中止宿主启动。具体业务集合的初始
     * 值由各模块的幂等初始化负责。</p>
     */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(workspaceRoot);
            Files.createDirectories(trashRoot);
            Files.createDirectories(stateRoot);
            this.workspaceReal = workspaceRoot.toRealPath();
            this.fileRoots = loadFileRoots();
        } catch (IOException exception) {
            throw new IllegalStateException("初始化本地数据目录失败", exception);
        }
    }

    @Override
    public String workspaceLabel() {
        return "本地演示工作区";
    }

    /** 返回当前显式授权根的不可变快照，不暴露可修改集合。 */
    @Override
    public synchronized List<AuthorizedFileRoot> listFileRoots() {
        return fileRoots.stream().map(FileRootRegistration::descriptor).toList();
    }

    /**
     * 登记一个用户明确选择的本地目录。
     *
     * <p>同一真实目录重复登记会更新标签与能力，不生成重复根。只接受存在的绝对目录；
     * 网络共享、设备命名空间和无法解析真实路径的目录会被拒绝。登记只改变宿主访问策略，
     * 不创建、移动或删除目标目录内容。</p>
     */
    public synchronized AuthorizedFileRoot authorizeFileRoot(
            String absolutePath,
            String label,
            boolean allowWrite,
            boolean allowDelete
    ) {
        String raw = Objects.requireNonNullElse(absolutePath, "").trim();
        if (raw.isBlank() || raw.startsWith("\\\\") || raw.startsWith("//")) {
            throw new IllegalArgumentException("文件根必须是非网络共享的本地目录");
        }
        Path candidate;
        try {
            candidate = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("文件根路径格式非法");
        }
        if (!candidate.isAbsolute()) {
            throw new IllegalArgumentException("文件根必须是绝对路径");
        }
        candidate = candidate.normalize();
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("文件根必须存在且为普通目录");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析文件根真实路径", exception);
        }
        String normalizedLabel = Objects.requireNonNullElse(label, "").trim();
        if (normalizedLabel.length() > 80) {
            throw new IllegalArgumentException("文件根名称不能超过 80 个字符");
        }
        if (normalizedLabel.isBlank()) {
            normalizedLabel = real.getFileName() == null
                    ? real.toString()
                    : real.getFileName().toString();
        }
        String rootId = UUID.nameUUIDFromBytes(
                real.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        ).toString();
        FileRootRegistration root = new FileRootRegistration(
                rootId,
                normalizedLabel,
                real.toString(),
                true,
                allowWrite,
                allowDelete
        );
        List<FileRootRegistration> updated = new ArrayList<>(fileRoots);
        updated.removeIf(entry -> entry.id().equals(rootId));
        updated.add(root);
        try {
            persistFileRoots(updated);
        } catch (IOException exception) {
            throw new IllegalStateException("保存文件根授权失败", exception);
        }
        this.fileRoots = List.copyOf(updated);
        return root.descriptor();
    }

    /**
     * 撤销一个文件根授权。
     *
     * <p>撤销只更新宿主授权清单并清理该根尚未使用的读取令牌，不移动、删除或修改
     * 授权目录中的任何真实文件。未知根标识直接拒绝。</p>
     */
    public synchronized AuthorizedFileRoot revokeFileRoot(String rootId) {
        FileRootRegistration removed = requireFileRoot(rootId);
        List<FileRootRegistration> updated = fileRoots.stream()
                .filter(root -> !root.id().equals(removed.id()))
                .toList();
        try {
            persistFileRoots(updated);
        } catch (IOException exception) {
            throw new IllegalStateException("保存文件根撤销结果失败", exception);
        }
        readGrants.entrySet().removeIf(entry -> entry.getValue().rootId().equals(removed.id()));
        this.fileRoots = List.copyOf(updated);
        return removed.descriptor();
    }

    /** 将显式授权根内相对路径解析为宿主受控路径。 */
    @Override
    public Path resolveAuthorizedPath(String rootId, String relativePath) {
        FileRootRegistration root = requireFileRoot(rootId);
        if (!root.read()) {
            throw new IllegalArgumentException("文件根未授予读取能力");
        }
        String text = Objects.requireNonNullElse(relativePath, "").trim();
        Path input;
        try {
            input = Path.of(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("路径格式非法");
        }
        if (input.isAbsolute()) {
            throw new IllegalArgumentException("授权根内只允许相对路径");
        }
        Path rootPath = Path.of(root.path()).normalize();
        Path candidate = rootPath.resolve(input).normalize();
        if (!candidate.startsWith(rootPath)) {
            throw new IllegalArgumentException("路径越过授权根边界");
        }
        ensurePathInsideRoot(candidate, rootPath);
        return candidate;
    }

    /** 列出授权根目录的直接子项，路径相对于该授权根。 */
    @Override
    public synchronized List<FileEntry> listAuthorizedDirectory(Path directory) {
        FileRootRegistration root = requireReadableRootFor(directory);
        Path rootPath = Path.of(root.path()).normalize();
        ensurePathInsideRoot(directory, rootPath);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是目录");
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> !isLink(path))
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> path.getFileName().toString()))
                    .map(path -> entry(path, rootPath))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取授权目录失败", exception);
        }
    }

    /**
     * 为本机用户当前选择签发短时读取令牌。
     *
     * <p>令牌和文件映射只保存在宿主内存；调用方响应和审计只接触随机令牌。路径不得
     * 进入模块 Action 参数或执行记录。</p>
     */
    public Map<String, Object> issueReadGrant(String rootId, String relativePath, String purpose) {
        Path file = resolveAuthorizedPath(rootId, relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("只能为普通文件签发读取令牌");
        }
        long now = System.currentTimeMillis();
        readGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        String token = newId();
        long expiresAt = now + 15L * 60L * 1000L;
        String normalizedPurpose = Objects.requireNonNullElse(purpose, "preview").trim();
        normalizedPurpose = normalizedPurpose.toLowerCase(Locale.ROOT);
        if (!normalizedPurpose.equals("preview") && !normalizedPurpose.equals("summary")) {
            throw new IllegalArgumentException("读取授权用途必须为 preview 或 summary");
        }
        readGrants.put(token, new ReadGrant(
                rootId,
                Objects.requireNonNullElse(relativePath, "").trim(),
                normalizedPurpose,
                expiresAt
        ));
        return Map.of("token", token, "expiresAt", expiresAt);
    }

    /** 凭宿主短时令牌分页读取文本、PDF、Office 或本机 OCR 正文。 */
    @Override
    public FileReadResult readGrantedText(String token, int offset, int maxChars) {
        ReadGrant grant = readGrants.get(Objects.requireNonNullElse(token, "").trim());
        if (grant == null || grant.expiresAt() < System.currentTimeMillis()) {
            throw new IllegalArgumentException("读取令牌不存在或已过期");
        }
        Path file = resolveAuthorizedPath(grant.rootId(), grant.relativePath());
        return readAuthorizedText(file, offset, maxChars);
    }

    /** 分页读取授权根普通文本或本地抽取正文。 */
    public synchronized FileReadResult readAuthorizedText(Path file, int offset, int maxChars) {
        requireReadableRootFor(file);
        ensurePathInsideRoot(file, Path.of(requireReadableRootFor(file).path()).normalize());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是普通文件");
        }
        try {
            long size = Files.size(file);
            if (!isPlainTextExtension(extension(file))) {
                FileContentExtractor.ExtractedText extracted = FileContentExtractor.extract(file);
                int safeOffset = Math.max(0, Math.min(offset, extracted.content().length()));
                int safeMax = Math.max(1, Math.min(maxChars <= 0 ? 8_000 : maxChars, MAX_READ_CHARS));
                int end = Math.min(extracted.content().length(), safeOffset + safeMax);
                return new FileReadResult(
                        extracted.content().substring(safeOffset, end),
                        safeOffset,
                        end,
                        size,
                        end >= extracted.content().length(),
                        extracted.encoding()
                );
            }
            if (size > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("文本文件超过 16 MiB 预览上限");
            }
            if (containsNullByte(file)) {
                throw new IllegalArgumentException("暂不支持读取二进制文件正文");
            }
            int safeOffset = Math.max(0, offset);
            int safeMax = Math.max(1, Math.min(maxChars <= 0 ? 8_000 : maxChars, MAX_READ_CHARS));
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                long skipped = 0;
                while (skipped < safeOffset) {
                    long step = reader.skip(safeOffset - skipped);
                    if (step <= 0) {
                        break;
                    }
                    skipped += step;
                }
                char[] buffer = new char[safeMax];
                int count = 0;
                while (count < buffer.length) {
                    int read = reader.read(buffer, count, buffer.length - count);
                    if (read < 0) {
                        break;
                    }
                    count += read;
                }
                int next = Math.toIntExact(Math.min(Integer.MAX_VALUE, skipped + count));
                boolean eof = count < safeMax || reader.read() < 0;
                return new FileReadResult(
                        new String(buffer, 0, count),
                        Math.toIntExact(Math.min(Integer.MAX_VALUE, skipped)),
                        next,
                        size,
                        eof,
                        "UTF-8"
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取文本文件失败", exception);
        }
    }

    /** 通过桌面默认关联程序打开授权根普通文件。 */
    @Override
    public void openFile(Path file) {
        Path base = requireManagedReadablePath(file);
        if (!base.equals(workspaceRoot)) {
            ensurePathInsideRoot(file, base);
        } else {
            ensureExistingAncestorIsSafe(file, workspaceRoot);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("只能打开普通文件");
        }
        if (!Desktop.isDesktopSupported()) {
            throw new IllegalStateException("当前系统不支持桌面默认应用打开");
        }
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException("默认应用打开文件失败", exception);
        }
    }

    /** 在授权根内创建 UTF-8 文本文件。 */
    @Override
    public Path createAuthorizedTextFile(Path file, String content) {
        return createTextFile(file, content);
    }

    /** 在授权根内写入文本；覆盖前把旧对象移入项目回收站。 */
    @Override
    public synchronized FileWriteResult writeAuthorizedTextFile(
            Path file,
            String content,
            boolean overwrite,
            String planId,
            String stepId
    ) {
        FileRootRegistration root = requireMutableRootFor(file);
        ensurePathInsideRoot(file, Path.of(root.path()).normalize());
        byte[] bytes = Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8);
        return writeBytes(file, bytes, overwrite, planId, stepId, root);
    }

    /** 将宿主暂存二进制文件复制到授权目标；覆盖前把旧对象移入项目回收站。 */
    @Override
    public synchronized FileWriteResult writeAuthorizedBinaryFile(
            Path stagedFile,
            Path target,
            boolean overwrite,
            String planId,
            String stepId
    ) {
        requireInsideWorkspace(stagedFile);
        ensureExistingAncestorIsSafe(stagedFile);
        if (!Files.isRegularFile(stagedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("上传暂存文件不存在");
        }
        FileRootRegistration root = requireMutableRootFor(target);
        ensurePathInsideRoot(target, Path.of(root.path()).normalize());
        try (InputStream input = Files.newInputStream(stagedFile)) {
            return writeStream(target, input, overwrite, planId, stepId, root);
        } catch (IOException exception) {
            throw new IllegalStateException("写入上传文件失败", exception);
        }
    }

    /** 返回工作区根路径；仅供宿主框架组件使用，不应作为模块 API 暴露。 */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** 返回应用自管数据根路径；仅供宿主审计、设置和执行记录组件使用。 */
    public Path dataRoot() {
        return dataRoot;
    }

    /**
     * 把工作区相对路径校验并解析为绝对候选路径。
     *
     * <p>拒绝空值以外的绝对路径和规范化越界，并检查最近已存在的祖先不是符号链接且
     * 真实路径仍位于工作区内。目标可以尚不存在。校验只读；格式、边界或 I/O 问题会
     * 抛出异常，不会创建目录或文件。</p>
     */
    @Override
    public Path resolveWorkspacePath(String relativePath) {
        String text = Objects.requireNonNullElse(relativePath, "").trim();
        Path input;
        try {
            input = Path.of(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("路径格式非法");
        }
        if (input.isAbsolute()) {
            throw new IllegalArgumentException("仅允许工作区相对路径");
        }
        Path candidate = workspaceRoot.resolve(input).normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越过工作区边界");
        }
        ensureExistingAncestorIsSafe(candidate);
        return candidate;
    }

    /**
     * 使用不跟随末尾链接的方式检查工作区路径是否存在。
     *
     * <p>前置条件是由宿主解析得到的受控路径。方法不读取目标内容，也不清理悬空链接；
     * 调用方不能把“存在”解释为普通文件、可读或路径祖先完全安全。</p>
     */
    @Override
    public boolean exists(Path path) {
        requireManagedReadablePath(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 返回目录直接子项的稳定摘要列表。
     *
     * <p>目录在前、同类按名称排序，条目不跟随末尾符号链接。目标不是目录或任一摘要
     * 读取失败时抛出异常；方法不递归，也不修改目录。</p>
     */
    @Override
    public List<FileEntry> listDirectory(Path directory) {
        Path base = requireManagedReadablePath(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是目录");
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> path.getFileName().toString()))
                    .map(path -> entry(path, base))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取目录失败", exception);
        }
    }

    /**
     * 创建目录及缺失父目录。
     *
     * <p>先验证现有祖先并拒绝已存在目标，然后调用文件系统创建。成功会持久化目录；
     * 多级创建中途失败时可能留下部分父目录，不提供跨路径事务回滚。</p>
     */
    @Override
    public Path createDirectory(Path directory) {
        Path base = requireManagedMutablePath(directory);
        ensureExistingAncestorIsSafe(directory, base);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标已存在，拒绝覆盖");
        }
        try {
            return Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("创建目录失败", exception);
        }
    }

    /**
     * 创建新的 UTF-8 文本文件。
     *
     * <p>目标已存在时失败，缺失父目录会被创建。内容为 {@code null} 时写入空字符串。
     * 方法会持久化文件；存在性预检与写入之间没有独立的跨进程锁。</p>
     */
    @Override
    public Path createTextFile(Path file, String content) {
        Path base = requireManagedMutablePath(file);
        ensureExistingAncestorIsSafe(file, base);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标已存在，拒绝覆盖");
        }
        try {
            Files.createDirectories(file.getParent());
            return Files.writeString(file, Objects.requireNonNullElse(content, ""), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("创建文本文件失败", exception);
        }
    }

    /**
     * 读取受控普通文件的 UTF-8 内容。
     *
     * <p>只接受工作区内普通文件，不跟随末尾链接。读取不修改文件；缺失、类型不符或
     * 解码/I/O 失败时抛出异常。</p>
     */
    @Override
    public String readTextFile(Path file) {
        Path base = requireManagedReadablePath(file);
        ensureExistingAncestorIsSafe(file, base);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是普通文件");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取文本文件失败", exception);
        }
    }

    /**
     * 创建宿主模块数据接口使用的二进制文件。
     *
     * <p>该方法不属于 {@link ModuleRuntime} SPI，仅供宿主数据服务调用。目标必须位于
     * 工作区且尚不存在；成功写入会持久化内容，失败时可能已创建父目录。</p>
     */
    public Path createBinaryFile(Path file, byte[] content) {
        requireInsideWorkspace(file);
        ensureExistingAncestorIsSafe(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标已存在，拒绝覆盖");
        }
        try {
            Files.createDirectories(file.getParent());
            return Files.write(file, Objects.requireNonNull(content));
        } catch (IOException exception) {
            throw new IllegalStateException("创建二进制文件失败", exception);
        }
    }

    /**
     * 在工作区内移动对象且拒绝覆盖现有目标。
     *
     * <p>源必须存在，源和目标必须位于工作区词法边界内，源不能是工作区根。目标祖先
     * 会经过安全校验。移动成功会立即改变路径；跨卷或底层失败时抛出异常，源与目标
     * 的最终状态须以文件系统为准。</p>
     */
    @Override
    public void move(Path source, Path target) {
        Path sourceBase = requireManagedMutablePath(source);
        Path targetBase = requireManagedMutablePath(target);
        ensureExistingAncestorIsSafe(target, targetBase);
        if (isManagedRoot(source)) {
            throw new IllegalArgumentException("禁止移动整个受控根目录");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("源对象不存在");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标已存在，拒绝覆盖");
        }
        try {
            Files.move(source, target);
        } catch (IOException exception) {
            throw new IllegalStateException("移动失败，未覆盖任何已有对象", exception);
        }
    }

    /**
     * 将工作区对象移入按计划与步骤分区的项目回收站。
     *
     * <p>方法在实例锁内完成移动和清单更新，避免同进程并发写入清单。源不能是工作区根；
     * 计划/步骤 ID 会被清理为安全目录名，重名对象追加随机前缀。移动与 JSON 清单写入
     * 不是单一事务，后一步失败会抛出异常但不会尝试物理删除已移动对象。</p>
     */
    @Override
    public synchronized TrashEntry moveToTrash(Path source, String planId, String stepId) {
        Path base = requireManagedDeletablePath(source);
        return moveToTrashInternal(source, base, planId, stepId);
    }

    /** 宿主内部回滚使用回收站，不要求用户额外授予直接删除能力。 */
    @Override
    public synchronized TrashEntry moveToTrashForRecovery(
            Path source,
            String planId,
            String stepId
    ) {
        Path base = requireManagedReadablePath(source);
        if (isProtectedMutationPath(source)) {
            throw new IllegalArgumentException("禁止在系统保护目录中执行恢复清理");
        }
        return moveToTrashInternal(source, base, planId, stepId);
    }

    /** 移动对象到项目回收站并写入清单；所有入口共享同一套边界校验。 */
    private TrashEntry moveToTrashInternal(
            Path source,
            Path base,
            String planId,
            String stepId
    ) {
        if (isManagedRoot(source)) {
            if (source.toAbsolutePath().normalize().equals(workspaceRoot)) {
                throw new IllegalArgumentException("禁止删除整个工作区");
            }
            throw new IllegalArgumentException("禁止删除整个受控根目录");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("待删除对象不存在");
        }
        String itemId = newId();
        String safePlan = sanitizeId(planId);
        String safeStep = sanitizeId(stepId);
        Path storageRoot = storageTrashRoot(source);
        Path stored = storageRoot.resolve(safePlan).resolve(safeStep).resolve(source.getFileName()).normalize();
        requireInside(storageRoot, stored, "回收站路径越界");
        try {
            Files.createDirectories(stored.getParent());
            if (Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                stored = stored.resolveSibling(newId() + "-" + stored.getFileName());
            }
            Files.move(source, stored);
            TrashEntry storedEntry = new TrashEntry(
                    itemId,
                    storeOriginalPath(source, base),
                    storeTrashPath(stored),
                    source.getFileName().toString(),
                    Instant.now().toString()
            );
            List<TrashEntry> entries = new ArrayList<>(readTrashManifest());
            entries.add(storedEntry);
            try {
                writeTrashManifest(entries);
            } catch (IOException manifestFailure) {
                try {
                    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                            && Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(stored, source);
                    }
                } catch (IOException restoreFailure) {
                    manifestFailure.addSuppressed(restoreFailure);
                }
                throw manifestFailure;
            }
            return publicTrashEntry(storedEntry);
        } catch (IOException exception) {
            throw new IllegalStateException("移入回收站失败", exception);
        }
    }

    /**
     * 从回收站清单恢复一个对象到其原工作区位置。
     *
     * <p>清单 ID、存储路径和原路径都必须有效，原位置存在时拒绝覆盖。恢复与清单移除
     * 在同一实例锁内执行，但不是跨文件事务；后半失败可能留下已恢复对象和过期清单。
     * 同一 ID 不会被重复恢复为成功结果。</p>
     */
    @Override
    public synchronized Path restoreFromTrash(String trashId) {
        List<TrashEntry> entries = new ArrayList<>(readTrashManifest());
        TrashEntry target = entries.stream()
                .filter(entry -> entry.id().equals(trashId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("回收站条目不存在"));
        Path stored = restoreStoredPath(target.storedPath());
        Path original = restoreOriginalPath(target.originalPath());
        Path base = requireManagedMutablePath(original);
        ensureExistingAncestorIsSafe(original, base);
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("原位置已有对象，拒绝覆盖");
        }
        try {
            Files.createDirectories(original.getParent());
            Files.move(stored, original);
            entries.removeIf(entry -> entry.id().equals(trashId));
            writeTrashManifest(entries);
            return original;
        } catch (IOException exception) {
            throw new IllegalStateException("从回收站恢复失败", exception);
        }
    }

    /** 读取回收站清单并按删除时间倒序返回；无清单视为空，损坏清单抛出异常。 */
    @Override
    public List<TrashEntry> listTrash() {
        return readTrashManifest().stream()
                .sorted(Comparator.comparing(TrashEntry::deletedAt).reversed())
                .map(this::publicTrashEntry)
                .toList();
    }

    /**
     * 读取指定模块集合的 JSON 对象数组。
     *
     * <p>文件不存在时返回空列表；存在时在实例锁内解析，并为每个顶层映射创建不可变
     * 副本。该方法不检查 owner，也不修复损坏或非对象数组内容，解析失败会抛出异常。</p>
     */
    @Override
    public synchronized List<Map<String, Object>> readItems(String collection) {
        Path file = stateFile(collection);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(file.toFile(), ITEMS_TYPE);
            return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块集合失败：" + collection, exception);
        }
    }

    /** 判断集合文件是否存在；不解析内容，也不区分空数组与损坏 JSON。 */
    @Override
    public synchronized boolean hasItems(String collection) {
        return Files.exists(stateFile(collection), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 通过同目录临时文件整体替换模块集合。
     *
     * <p>集合名先按宿主规则校验。JSON 写入完成后优先原子替换，平台不支持时回退为
     * 普通替换。成功会覆盖旧集合并持久化；异常可能在应用目录留下临时文件。方法不解析
     * 集合 owner，模块独占性仍由命名和审查约束保证。</p>
     */
    @Override
    public synchronized void writeItems(String collection, List<Map<String, Object>> items) {
        Path file = stateFile(collection);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), items);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入模块集合失败：" + collection, exception);
        }
    }

    /** 生成随机 UUID；不检查是否与已有回收站或模块实体重名。 */
    @Override
    public String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 把工作区或回收站路径转换为安全的相对字符串。
     *
     * <p>工作区路径直接相对工作区根，回收站路径加 {@code trash/} 前缀并统一为正斜杠。
     * 方法只做规范化和词法归属检查，不验证真实路径或创建任何对象。</p>
     */
    @Override
    public String relativePath(Path path) {
        if (path.normalize().startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(path.normalize()).toString().replace('\\', '/');
        }
        if (path.normalize().startsWith(trashRoot)) {
            return "trash/" + trashRoot.relativize(path.normalize()).toString().replace('\\', '/');
        }
        Optional<FileRootRegistration> root = authorizedRootFor(path);
        if (root.isPresent()) {
            Path rootPath = Path.of(root.get().path()).normalize();
            String relative = rootPath.relativize(path.normalize()).toString().replace('\\', '/');
            return root.get().id() + ":" + relative;
        }
        throw new IllegalArgumentException("路径不在受保护目录内");
    }

    /**
     * 生成不跟随链接的单个目录项摘要。
     *
     * <p>名称和相对路径来自受控路径，大小与修改时间来自文件系统属性。属性读取或时间
     * 格式化依赖失败时抛出异常，不返回占位条目。</p>
     */
    private FileEntry entry(Path path, Path base) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            return new FileEntry(
                    path.getFileName().toString(),
                    relativeForEntry(path, base),
                    attributes.isDirectory(),
                    attributes.size(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.systemDefault())
                            .format(attributes.lastModifiedTime().toInstant())
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取文件摘要失败", exception);
        }
    }

    /** 生成目录项的根内相对路径；工作区沿用原相对路径，授权根不带绝对盘符。 */
    private String relativeForEntry(Path path, Path base) {
        return base.relativize(path.normalize()).toString().replace('\\', '/');
    }

    /** 执行工作区词法边界检查；调用方仍须按具体操作执行真实路径校验。 */
    private void requireInsideWorkspace(Path path) {
        requireInside(workspaceRoot, path.normalize(), "路径越过工作区边界");
    }

    /** 检查规范化路径是否以指定受控根开始；不解析符号链接。 */
    private void requireInside(Path root, Path path, String message) {
        if (!path.normalize().startsWith(root)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验路径最近已存在祖先的真实位置仍在工作区内。
     *
     * <p>从目标向上寻找首个存在项，拒绝符号链接，并把其真实路径与初始化时记录的工作区
     * 真实根比较，防止通过祖先链接逃逸。运行时尚未初始化或真实路径解析失败时抛出异常。</p>
     */
    private void ensureExistingAncestorIsSafe(Path path) {
        ensureExistingAncestorIsSafe(path, workspaceRoot);
    }

    /** 校验路径最近已存在祖先的真实位置仍位于指定受控根内。 */
    private void ensureExistingAncestorIsSafe(Path path, Path root) {
        if (workspaceReal == null) {
            throw new IllegalStateException("工作区尚未初始化");
        }
        Path normalizedRoot = root.normalize();
        Path realRoot;
        try {
            realRoot = Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                    ? normalizedRoot.toRealPath()
                    : normalizedRoot;
        } catch (IOException exception) {
            throw new IllegalStateException("解析受控根路径失败", exception);
        }
        Path cursor = path;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalArgumentException("路径没有有效父目录");
        }
        if (Files.isSymbolicLink(cursor)) {
            throw new IllegalArgumentException("受控路径不允许符号链接或重解析点");
        }
        try {
            Path real = cursor.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("路径解析后越过受控边界");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("校验受控路径失败", exception);
        }
    }

    /** 读取文件根 JSON；首次启动保持空授权，等待本机用户显式登记目录。 */
    private List<FileRootRegistration> loadFileRoots() throws IOException {
        if (!Files.exists(fileRootsFile, LinkOption.NOFOLLOW_LINKS)) {
            List<FileRootRegistration> initial = List.of();
            persistFileRoots(initial);
            return initial;
        }
        List<FileRootRegistration> loaded = objectMapper.readValue(fileRootsFile.toFile(), FILE_ROOTS_TYPE);
        List<FileRootRegistration> validated = new ArrayList<>();
        for (FileRootRegistration root : loaded) {
            if (root == null || root.id() == null || root.id().isBlank()
                    || root.path() == null || root.path().isBlank()) {
                throw new IllegalStateException("文件根配置包含缺失字段");
            }
            Path path;
            try {
                path = Path.of(root.path()).toAbsolutePath().normalize();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("文件根配置包含非法路径：" + root.path(), exception);
            }
            if (!path.isAbsolute()) {
                throw new IllegalStateException("文件根配置必须是绝对路径：" + root.path());
            }
            if (validated.stream().anyMatch(entry -> entry.id().equals(root.id()))) {
                throw new IllegalStateException("文件根配置包含重复 id：" + root.id());
            }
            validated.add(new FileRootRegistration(
                    root.id(),
                    root.label() == null || root.label().isBlank() ? path.toString() : root.label(),
                    path.toString(),
                    root.read(),
                    root.write(),
                    root.delete()
            ));
        }
        return List.copyOf(validated);
    }

    /** 原子替换文件根注册表；失败直接阻止启动或授权操作成功。 */
    private synchronized void persistFileRoots(List<FileRootRegistration> roots) throws IOException {
        Files.createDirectories(fileRootsFile.getParent());
        Path temporary = fileRootsFile.resolveSibling(fileRootsFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), roots);
        try {
            Files.move(temporary, fileRootsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, fileRootsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 按 id 查找授权根，未知 id 直接拒绝。 */
    private FileRootRegistration requireFileRoot(String rootId) {
        String id = Objects.requireNonNullElse(rootId, "").trim();
        return fileRoots.stream()
                .filter(root -> root.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文件根不存在或未授权"));
    }

    /** 查找路径所属的授权根；嵌套根优先选择最长路径。 */
    private Optional<FileRootRegistration> authorizedRootFor(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return fileRoots.stream()
                .filter(root -> normalized.startsWith(Path.of(root.path()).normalize()))
                .max(Comparator.comparingInt(root -> root.path().length()));
    }

    /** 要求路径属于可读授权根。 */
    private FileRootRegistration requireReadableRootFor(Path path) {
        FileRootRegistration root = authorizedRootFor(path)
                .orElseThrow(() -> new IllegalArgumentException("路径不在授权文件根内"));
        if (!root.read()) {
            throw new IllegalArgumentException("文件根未授予读取能力");
        }
        return root;
    }

    /** 要求路径属于可写授权根，并拒绝系统保护路径。 */
    private FileRootRegistration requireMutableRootFor(Path path) {
        FileRootRegistration root = requireReadableRootFor(path);
        if (!root.write()) {
            throw new IllegalArgumentException("文件根未授予写入能力");
        }
        if (isProtectedMutationPath(path)) {
            throw new IllegalArgumentException("禁止写入操作系统或系统保护目录");
        }
        ensurePathInsideRoot(path, Path.of(root.path()).normalize());
        return root;
    }

    /** 要求路径属于可删除授权根，并拒绝系统保护路径。 */
    private FileRootRegistration requireDeletableRootFor(Path path) {
        FileRootRegistration root = requireReadableRootFor(path);
        if (!root.delete()) {
            throw new IllegalArgumentException("文件根未授予删除能力");
        }
        if (isProtectedMutationPath(path)) {
            throw new IllegalArgumentException("禁止删除操作系统或系统保护目录");
        }
        ensurePathInsideRoot(path, Path.of(root.path()).normalize());
        return root;
    }

    /** 返回可读路径的根内基路径；工作区保留原语义。 */
    private Path requireManagedReadablePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot;
        }
        FileRootRegistration root = requireReadableRootFor(normalized);
        Path rootPath = Path.of(root.path()).normalize();
        ensurePathInsideRoot(normalized, rootPath);
        return rootPath;
    }

    /** 返回可写路径的根内基路径；工作区保留原语义。 */
    private Path requireManagedMutablePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot;
        }
        FileRootRegistration root = requireMutableRootFor(normalized);
        return Path.of(root.path()).normalize();
    }

    /** 返回可删除路径的根内基路径；工作区保留原语义。 */
    private Path requireManagedDeletablePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot;
        }
        FileRootRegistration root = requireDeletableRootFor(normalized);
        return Path.of(root.path()).normalize();
    }

    /** 检查目标或其最近祖先真实位置位于给定授权根内。 */
    private void ensurePathInsideRoot(Path path, Path root) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("路径越过授权根边界");
        }
        ensureExistingAncestorIsSafe(normalized, normalizedRoot);
    }

    /** 禁止覆盖或删除 Windows/Unix 系统级目录及其内容。 */
    private boolean isProtectedMutationPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null) {
            return true;
        }
        Path relative = root.relativize(normalized);
        if (relative.getNameCount() == 0) {
            return true;
        }
        String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return (windows ? PROTECTED_WINDOWS_DIRECTORIES : PROTECTED_UNIX_DIRECTORIES).contains(first);
    }

    /** 判断路径是否为工作区或任一授权根本身。 */
    private boolean isManagedRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(workspaceRoot)) {
            return true;
        }
        return fileRoots.stream()
                .anyMatch(root -> normalized.equals(Path.of(root.path()).normalize()));
    }

    /** 选择目标文件所在卷的项目回收站，避免跨卷移动删除风险。 */
    private Path storageTrashRoot(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return trashRoot;
        }
        Path sourceRoot = normalized.getRoot();
        Path dataRootPath = dataRoot.getRoot();
        if (sourceRoot != null && sourceRoot.equals(dataRootPath)) {
            return trashRoot;
        }
        if (sourceRoot == null) {
            return trashRoot;
        }
        return sourceRoot.resolve(PER_VOLUME_TRASH_DIRECTORY).normalize();
    }

    /** 工作区保存相对路径，授权根保存根 ID 与根内相对路径。 */
    private String storeOriginalPath(Path source, Path base) {
        Path normalized = source.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
        }
        FileRootRegistration root = authorizedRootFor(normalized)
                .orElseThrow(() -> new IllegalArgumentException("授权根原路径不属于任何文件根"));
        String relative = root.rootPath().relativize(normalized).toString().replace('\\', '/');
        return root.id() + ":" + relative;
    }

    /** 数据目录回收站写相对路径，其他卷回收站写入不含用户目录的卷标识。 */
    private String storeTrashPath(Path stored) {
        Path normalized = stored.toAbsolutePath().normalize();
        if (normalized.startsWith(trashRoot)) {
            return trashRoot.relativize(normalized).toString().replace('\\', '/');
        }
        Path volumeRoot = normalized.getRoot();
        if (volumeRoot == null) {
            throw new IllegalArgumentException("回收站存储路径缺少卷根");
        }
        Path volumeTrash = volumeRoot.resolve(PER_VOLUME_TRASH_DIRECTORY).normalize();
        requireInside(volumeTrash, normalized, "回收站存储路径越界");
        String volume = volumeRoot.toString().replace("\\", "").replace("/", "");
        String relative = volumeTrash.relativize(normalized).toString().replace('\\', '/');
        return "volume:" + volume + "/" + relative;
    }

    /** 恢复时把相对或绝对存储路径还原为受控回收站路径。 */
    private Path restoreStoredPath(String storedPath) {
        String storedText = Objects.requireNonNullElse(storedPath, "");
        if (storedText.startsWith("volume:")) {
            String value = storedText.substring("volume:".length());
            int separator = value.indexOf('/');
            if (separator <= 0) {
                throw new IllegalArgumentException("回收站卷标识非法");
            }
            String volume = value.substring(0, separator);
            String relative = value.substring(separator + 1);
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (!windows || !volume.matches("[A-Za-z]")) {
                throw new IllegalArgumentException("回收站卷标识不受支持");
            }
            Path stored = Path.of(volume + ":\\" + PER_VOLUME_TRASH_DIRECTORY)
                    .resolve(relative)
                    .normalize();
            Path volumeTrash = Path.of(volume + ":\\" + PER_VOLUME_TRASH_DIRECTORY).normalize();
            requireInside(volumeTrash, stored, "回收站路径越界");
            return stored;
        }
        Path candidate;
        try {
            candidate = Path.of(storedText);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("回收站存储路径非法", exception);
        }
        if (candidate.isAbsolute()) {
            Path normalized = candidate.toAbsolutePath().normalize();
            Path root = normalized.getRoot();
            if (normalized.startsWith(trashRoot)
                    || (root != null && normalized.startsWith(
                    root.resolve(PER_VOLUME_TRASH_DIRECTORY).normalize()))) {
                return normalized;
            }
            throw new IllegalArgumentException("回收站存储路径越界");
        }
        Path resolved = trashRoot.resolve(candidate).normalize();
        requireInside(trashRoot, resolved, "回收站路径越界");
        return resolved;
    }

    /** 恢复时识别工作区相对路径或授权根绝对路径。 */
    private Path restoreOriginalPath(String originalPath) {
        String text = Objects.requireNonNullElse(originalPath, "");
        int separator = text.indexOf(':');
        if (separator == 36) {
            String rootId = text.substring(0, separator);
            String relative = text.substring(separator + 1);
            return resolveAuthorizedPath(rootId, relative);
        }
        Path candidate;
        try {
            candidate = Path.of(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("回收站原路径非法", exception);
        }
        return candidate.isAbsolute() ? candidate.toAbsolutePath().normalize() : resolveWorkspacePath(text);
    }

    /** 将持久化回收站记录转换为不含宿主机绝对路径的模块可见视图。 */
    private TrashEntry publicTrashEntry(TrashEntry entry) {
        String originalPath = entry.originalPath();
        Path originalCandidate;
        try {
            originalCandidate = Path.of(Objects.requireNonNullElse(originalPath, ""));
        } catch (RuntimeException exception) {
            originalCandidate = null;
        }
        Path original = originalCandidate;
        if (original != null && original.isAbsolute()) {
            Optional<FileRootRegistration> root = authorizedRootFor(original);
            originalPath = root
                    .map(value -> value.id() + ":"
                            + value.rootPath().relativize(original.toAbsolutePath().normalize())
                            .toString().replace('\\', '/'))
                    .orElse("external:" + entry.name());
        }
        String storedPath = entry.storedPath();
        Path stored;
        try {
            stored = Path.of(Objects.requireNonNullElse(storedPath, ""));
        } catch (RuntimeException exception) {
            stored = null;
        }
        if (stored != null && stored.isAbsolute() && !stored.startsWith(trashRoot)) {
            Path volumeRoot = stored.getRoot();
            Path volumeTrash = volumeRoot == null
                    ? null
                    : volumeRoot.resolve(PER_VOLUME_TRASH_DIRECTORY).normalize();
            if (volumeTrash != null && stored.startsWith(volumeTrash)) {
                String volume = volumeRoot.toString().replace("\\", "").replace("/", "");
                String relative = volumeTrash.relativize(stored).toString().replace('\\', '/');
                storedPath = "volume:" + volume + "/" + relative;
            } else {
                storedPath = "trash/" + entry.name();
            }
        } else if (stored != null && stored.isAbsolute()) {
            storedPath = trashRoot.relativize(stored).toString().replace('\\', '/');
        }
        return new TrashEntry(
                entry.id(),
                originalPath,
                storedPath,
                entry.name(),
                entry.deletedAt()
        );
    }

    /** 以项目回收站备份和目标目录临时文件保证普通用户文件覆盖可回滚。 */
    private FileWriteResult writeBytes(
            Path target,
            byte[] bytes,
            boolean overwrite,
            String planId,
            String stepId,
            FileRootRegistration root
    ) {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            return writeStream(target, input, overwrite, planId, stepId, root);
        } catch (IOException exception) {
            throw new IllegalStateException("写入文本文件失败", exception);
        }
    }

    /** 流式写入目标；任何失败都尝试恢复被替换的旧文件。 */
    private FileWriteResult writeStream(
            Path target,
            InputStream input,
            boolean overwrite,
            String planId,
            String stepId,
            FileRootRegistration root
    ) {
        Path normalized = target.toAbsolutePath().normalize();
        Path rootPath = Path.of(root.path()).normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw new IllegalArgumentException("目标已存在，拒绝覆盖");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是普通文件，拒绝覆盖");
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("目标缺少父目录");
        }
        TrashEntry backup = null;
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            ensureExistingAncestorIsSafe(normalized, rootPath);
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                backup = moveToTrashForRecovery(normalized, planId, "backup-" + stepId);
            }
            temporary = parent.resolve(".orderly-write-" + UUID.randomUUID() + ".tmp");
            Files.copy(input, temporary);
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, normalized);
            }
            return new FileWriteResult(
                    rootPath.relativize(normalized).toString().replace('\\', '/'),
                    Files.size(normalized),
                    backup
            );
        } catch (IOException exception) {
            if (temporary != null && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveToTrashForRecovery(temporary, planId, "failed-" + stepId);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (backup != null) {
                try {
                    restoreFromTrash(backup.id());
                } catch (RuntimeException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw new IllegalStateException("写入文件失败，已尝试恢复原对象", exception);
        }
    }

    /** 只嗅探前 8 KiB 的 NUL 字节，避免把二进制误当文本发给界面或模型。 */
    private boolean containsNullByte(Path file) throws IOException {
        byte[] sample = new byte[8 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int count = input.read(sample);
            for (int index = 0; index < count; index++) {
                if (sample[index] == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 文本文件走流式分页；PDF、Office 和图片走宿主本地正文抽取。 */
    private boolean isPlainTextExtension(String extension) {
        return switch (extension) {
            case "", "txt", "md", "json", "jsonl", "csv", "tsv", "log", "xml",
                    "yaml", "yml", "html", "htm", "css", "js", "mjs", "cjs",
                    "ts", "tsx", "jsx", "java", "py", "ps1", "cmd", "bat",
                    "sh", "sql", "ini", "toml" -> true;
            default -> false;
        };
    }

    /** 提取文件扩展名并转为小写。 */
    private String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 不跟随末尾链接判断符号链接。 */
    private boolean isLink(Path path) {
        return Files.isSymbolicLink(path);
    }

    /**
     * 将集合名映射到应用状态目录中的 JSON 文件。
     *
     * <p>只接受小写字母开头、总长受限且仅含小写字母、数字、下划线或连字符的名称。
     * 非法名称在任何文件操作前失败；方法不创建目录或文件。</p>
     */
    private Path stateFile(String collection) {
        if (collection == null || !collection.matches("[a-z][a-z0-9_-]{0,40}")) {
            throw new IllegalArgumentException("集合名称非法");
        }
        return stateRoot.resolve(collection + ".json");
    }

    /**
     * 读取回收站 JSON 清单。
     *
     * <p>清单不存在时返回空列表。存在时按数组解析并把每项转换为 {@link TrashEntry}；
     * 格式或 I/O 错误会抛出异常，不会静默丢弃损坏条目。</p>
     */
    private List<TrashEntry> readTrashManifest() {
        Path manifest = trashRoot.resolve("manifest.json");
        if (!Files.exists(manifest)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(manifest.toFile(), ITEMS_TYPE);
            return raw.stream().map(item -> new TrashEntry(
                    String.valueOf(item.get("id")),
                    String.valueOf(item.get("originalPath")),
                    String.valueOf(item.get("storedPath")),
                    String.valueOf(item.get("name")),
                    String.valueOf(item.get("deletedAt"))
            )).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取回收站清单失败", exception);
        }
    }

    /**
     * 原子或回退替换回收站清单文件。
     *
     * <p>调用方负责持有回收站实例锁。方法先写固定名称临时文件，再优先原子替换；失败
     * 通过 {@link IOException} 交给上层统一报错，不在此处吞掉异常。</p>
     */
    private void writeTrashManifest(List<TrashEntry> entries) throws IOException {
        Path manifest = trashRoot.resolve("manifest.json");
        Path temporary = manifest.resolveSibling("manifest.json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(temporary.toFile(), entries.stream().map(TrashEntry::asMap).toList());
        try {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 把计划或步骤 ID 限制为文件系统安全的单层目录名；空值归一为 {@code unknown}。 */
    private String sanitizeId(String id) {
        String value = Objects.requireNonNullElse(id, "unknown").replaceAll("[^a-zA-Z0-9_-]", "_");
        return value.isBlank() ? "unknown" : value;
    }

    /** 宿主内存中的短时读取授权，不持久化、不进入模块状态。 */
    private record ReadGrant(
            String rootId,
            String relativePath,
            String purpose,
            long expiresAt
    ) {
    }
}
