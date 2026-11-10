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
 * 本地沙箱运行时。
 *
 * <p>所有模块只能通过本类访问工作区、回收站和集合状态。路径解析会拒绝绝对
 * 路径、越过工作区根目录的路径和现有符号链接。代码不提供物理删除能力。</p>
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

    /** 创建数据目录并写入首次运行的演示数据。 */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(workspaceRoot.resolve("收件箱"));
            Files.createDirectories(workspaceRoot.resolve("待整理"));
            Files.createDirectories(trashRoot);
            Files.createDirectories(stateRoot);
            this.workspaceReal = workspaceRoot.toRealPath();
            seedFile("收件箱/示例笔记.txt", "这是归序本地演示工作区。\n可以安全地移动或删除后再回滚。\n");
            seedFile("待整理/草案.txt", "待整理草案\n");
            seedItems("todos", List.of(Map.of(
                    "id", "todo-seed-1",
                    "title", "完成季度复盘",
                    "dueAt", "2026-11-20T18:00:00+08:00",
                    "status", "OPEN",
                    "createdAt", "2026-11-10T09:00:00+08:00"
            )));
            seedItems("calendar", List.of(Map.of(
                    "id", "calendar-seed-1",
                    "title", "项目评审",
                    "startAt", "2026-11-18T10:00:00+08:00",
                    "endAt", "2026-11-18T11:00:00+08:00"
            )));
        } catch (IOException exception) {
            throw new IllegalStateException("初始化本地数据目录失败", exception);
        }
    }

    @Override
    public String workspaceLabel() {
        return "本地演示工作区";
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path dataRoot() {
        return dataRoot;
    }

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

    @Override
    public boolean exists(Path path) {
        requireInsideWorkspace(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

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

    @Override
    public List<TrashEntry> listTrash() {
        return readTrashManifest().stream()
                .sorted(Comparator.comparing(TrashEntry::deletedAt).reversed())
                .toList();
    }

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

    @Override
    public String newId() {
        return UUID.randomUUID().toString();
    }

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

    private void requireInsideWorkspace(Path path) {
        requireInside(workspaceRoot, path.normalize(), "路径越过工作区边界");
    }

    private void requireInside(Path root, Path path, String message) {
        if (!path.normalize().startsWith(root)) {
            throw new IllegalArgumentException(message);
        }
    }

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

    private Path stateFile(String collection) {
        if (collection == null || !collection.matches("[a-z][a-z0-9_-]{0,40}")) {
            throw new IllegalArgumentException("集合名称非法");
        }
        return stateRoot.resolve(collection + ".json");
    }

    private void seedFile(String relativePath, String content) throws IOException {
        Path path = resolveWorkspacePath(relativePath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }

    private void seedItems(String collection, List<Map<String, Object>> seed) throws IOException {
        if (!Files.exists(stateFile(collection))) {
            writeItems(collection, seed);
        }
    }

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

    private String sanitizeId(String id) {
        String value = Objects.requireNonNullElse(id, "unknown").replaceAll("[^a-zA-Z0-9_-]", "_");
        return value.isBlank() ? "unknown" : value;
    }
}
