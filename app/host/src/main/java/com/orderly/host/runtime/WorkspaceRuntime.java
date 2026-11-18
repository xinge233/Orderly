package com.orderly.host.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.TrashEntry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 归序应用自管目录上的本地模块运行时。
 *
 * <p>工作区、回收站和集合状态都位于配置的应用数据目录下。模块通过该实现执行受限
 * 路径解析、文件写入、回收站移动和集合持久化；框架不向模块公开物理删除能力。路径
 * 解析会拒绝绝对路径、词法越界和经过现有符号链接的逃逸，但外部模块 JAR 本身仍是
 * 本地受信任代码，而不是恶意代码安全沙箱。</p>
 */
@Component
public class WorkspaceRuntime implements ModuleRuntime {

    private static final TypeReference<List<Map<String, Object>>> ITEMS_TYPE = new TypeReference<>() {
    };

    private final Path dataRoot;
    private final Path workspaceRoot;
    private final Path trashRoot;
    private final Path stateRoot;
    private final ObjectMapper objectMapper;
    private Path workspaceReal;

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
        } catch (IOException exception) {
            throw new IllegalStateException("初始化本地数据目录失败", exception);
        }
    }

    @Override
    public String workspaceLabel() {
        return "本地演示工作区";
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
        requireInsideWorkspace(path);
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
        requireInsideWorkspace(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是目录");
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> path.getFileName().toString()))
                    .map(this::entry)
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
        requireInsideWorkspace(directory);
        ensureExistingAncestorIsSafe(directory);
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
        requireInsideWorkspace(file);
        ensureExistingAncestorIsSafe(file);
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
        requireInsideWorkspace(file);
        ensureExistingAncestorIsSafe(file);
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
        requireInsideWorkspace(source);
        requireInsideWorkspace(target);
        if (source.equals(workspaceRoot)) {
            throw new IllegalArgumentException("禁止移动整个工作区");
        }
        ensureExistingAncestorIsSafe(target);
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
        requireInsideWorkspace(source);
        if (source.equals(workspaceRoot)) {
            throw new IllegalArgumentException("禁止删除整个工作区");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("待删除对象不存在");
        }
        String itemId = newId();
        String safePlan = sanitizeId(planId);
        String safeStep = sanitizeId(stepId);
        Path stored = trashRoot.resolve(safePlan).resolve(safeStep).resolve(source.getFileName()).normalize();
        requireInside(trashRoot, stored, "回收站路径越界");
        try {
            Files.createDirectories(stored.getParent());
            if (Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                stored = stored.resolveSibling(newId() + "-" + stored.getFileName());
            }
            Files.move(source, stored);
            TrashEntry entry = new TrashEntry(
                    itemId,
                    relativePath(source),
                    trashRoot.relativize(stored).toString().replace('\\', '/'),
                    source.getFileName().toString(),
                    Instant.now().toString()
            );
            List<TrashEntry> entries = new ArrayList<>(readTrashManifest());
            entries.add(entry);
            writeTrashManifest(entries);
            return entry;
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
        Path stored = trashRoot.resolve(target.storedPath()).normalize();
        requireInside(trashRoot, stored, "回收站路径越界");
        Path original = resolveWorkspacePath(target.originalPath());
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
        throw new IllegalArgumentException("路径不在受保护目录内");
    }

    /**
     * 生成不跟随链接的单个目录项摘要。
     *
     * <p>名称和相对路径来自受控路径，大小与修改时间来自文件系统属性。属性读取或时间
     * 格式化依赖失败时抛出异常，不返回占位条目。</p>
     */
    private FileEntry entry(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            return new FileEntry(
                    path.getFileName().toString(),
                    relativePath(path),
                    attributes.isDirectory(),
                    attributes.size(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.systemDefault())
                            .format(attributes.lastModifiedTime().toInstant())
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取文件摘要失败", exception);
        }
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
        if (workspaceReal == null) {
            throw new IllegalStateException("工作区尚未初始化");
        }
        Path cursor = path;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalArgumentException("路径没有有效父目录");
        }
        if (Files.isSymbolicLink(cursor)) {
            throw new IllegalArgumentException("工作区不允许符号链接");
        }
        try {
            Path real = cursor.toRealPath();
            if (!real.startsWith(workspaceReal)) {
                throw new IllegalArgumentException("路径解析后越过工作区边界");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("校验工作区路径失败", exception);
        }
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
}
