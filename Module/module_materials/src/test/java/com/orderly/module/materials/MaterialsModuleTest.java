package com.orderly.module.materials;

import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.TrashEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialsModuleTest {

    @TempDir
    Path temp;

    private MaterialsModule module;
    private ModuleRuntime runtime;
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        module = new MaterialsModule();
        runtime = newRuntime(workspace, temp.resolve("trash"));
        module.initialize(runtime);
    }

    @Test
    void initializesAnEmptyOwnedCollectionIdempotently() {
        module.initialize(runtime);
        module.initialize(runtime);

        assertThat(runtime.readItems(MaterialsModule.ITEMS)).isEmpty();
        assertThat(module.query("materials.list", Map.of(), runtime))
                .containsEntry("count", 0);
        assertThat(module.query("materials.trash.list", Map.of(), runtime))
                .containsEntry("count", 0);
    }

    @Test
    void importsListsUpdatesAndRollsBackMetadata() throws IOException {
        Path upload = stageUpload("pdf", "%PDF-1.7\nmaterial".getBytes(StandardCharsets.UTF_8));
        ModuleInvocation importInvocation = invocation(
                "materials.import",
                Map.of(
                        "uploadPath", uploadArgument(upload),
                        "fileName", "系统设计.pdf",
                        "title", "系统设计资料",
                        "category", "课程",
                        "tags", "系统, 设计，课程",
                        "description", "架构设计讲义",
                        "contentType", "application/pdf"
                )
        );
        ModuleResult imported = module.execute(importInvocation, runtime);
        Map<String, Object> importedItem = nested(imported.output(), "item");
        String id = text(importedItem.get("id"));

        assertThat(runtime.exists(upload)).isFalse();
        assertThat(importedItem)
                .containsEntry("title", "系统设计资料")
                .containsEntry("category", "课程")
                .containsEntry("format", "PDF")
                .containsEntry("status", "ACTIVE");
        assertThat(importedItem.get("tags")).isEqualTo(List.of("系统", "设计", "课程"));
        assertThat(runtime.exists(runtime.resolveWorkspacePath(
                "modules/module_materials/files/" + id + "/file.pdf"
        ))).isTrue();
        assertThat(module.query("materials.list", Map.of(), runtime))
                .containsEntry("count", 1);

        ModuleInvocation update = invocation(
                "materials.update",
                Map.of(
                        "id", id,
                        "title", "系统架构设计",
                        "category", "工作",
                        "tags", "架构",
                        "description", "已归档"
                )
        );
        Map<String, Object> snapshot = module.prepareSnapshot(update, runtime);
        ModuleResult updated = module.execute(update, runtime);
        assertThat(nested(updated.output(), "after"))
                .containsEntry("title", "系统架构设计")
                .containsEntry("category", "工作");

        module.rollback(update, snapshot, updated.output(), runtime);
        assertThat(runtime.readItems(MaterialsModule.ITEMS))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("title", "系统设计资料")
                        .containsEntry("category", "课程"));

        ModuleResult importRolledBack = module.rollback(
                importInvocation,
                Map.of("items", List.of()),
                imported.output(),
                runtime
        );
        assertThat(importRolledBack.message()).contains("撤销");
        assertThat(runtime.readItems(MaterialsModule.ITEMS)).isEmpty();
        assertThat(runtime.exists(runtime.resolveWorkspacePath(
                "modules/module_materials/files/" + id
        ))).isFalse();
        assertThat(runtime.listTrash()).hasSize(1);
    }

    @Test
    void trashesRestoresAndRollsBackFileStateThroughProjectTrash() throws IOException {
        String id = importTextMaterial();
        ModuleInvocation trash = invocation("materials.trash", Map.of("id", id));
        Map<String, Object> snapshot = module.prepareSnapshot(trash, runtime);
        ModuleResult trashed = module.execute(trash, runtime);
        String trashId = text(nested(trashed.output(), "trashEntry").get("id"));

        assertThat(trashId).isNotBlank();
        assertThat(runtime.exists(runtime.resolveWorkspacePath(
                "modules/module_materials/files/" + id
        ))).isFalse();
        assertThat(module.query("materials.list", Map.of(), runtime))
                .containsEntry("count", 0);
        assertThat(module.query("materials.trash.list", Map.of(), runtime))
                .containsEntry("count", 1);

        module.rollback(trash, snapshot, trashed.output(), runtime);
        assertThat(module.query("materials.list", Map.of(), runtime))
                .containsEntry("count", 1);
        assertThat(runtime.exists(runtime.resolveWorkspacePath(
                "modules/module_materials/files/" + id
        ))).isTrue();

        ModuleInvocation trashAgain = invocation("materials.trash", Map.of("id", id));
        ModuleResult trashedAgain = module.execute(trashAgain, runtime);
        ModuleInvocation restore = invocation("materials.restore", Map.of("id", id));
        ModuleResult restored = module.execute(restore, runtime);
        assertThat(nested(restored.output(), "item"))
                .containsEntry("status", "ACTIVE");
        assertThat(runtime.exists(runtime.resolveWorkspacePath(
                "modules/module_materials/files/" + id
        ))).isTrue();

        module.rollback(restore, Map.of(), restored.output(), runtime);
        assertThat(module.query("materials.list", Map.of(), runtime))
                .containsEntry("count", 0);
        assertThat(module.query("materials.trash.list", Map.of(), runtime))
                .containsEntry("count", 1);
        assertThat(trashedAgain.output()).containsKey("item");
    }

    @Test
    void rejectsUnsafeUploadPathsFormatsAndMissingActions() throws IOException {
        Path upload = stageUpload("exe", new byte[]{0x4D, 0x5A});
        assertThatThrownBy(() -> module.execute(invocation(
                "materials.import",
                Map.of(
                        "uploadPath", uploadArgument(upload),
                        "fileName", "tool.exe",
                        "title", "工具"
                )
        ), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");

        assertThatThrownBy(() -> module.execute(invocation(
                "materials.import",
                Map.of(
                        "uploadPath", "../outside.pdf",
                        "fileName", "outside.pdf",
                        "title", "越界"
                )
        ), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上传路径非法");

        assertThatThrownBy(() -> module.execute(invocation(
                "materials.unknown",
                Map.of()
        ), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知");
    }

    private String importTextMaterial() throws IOException {
        Path upload = stageUpload("txt", "资料正文".getBytes(StandardCharsets.UTF_8));
        ModuleResult imported = module.execute(invocation(
                "materials.import",
                Map.of(
                        "uploadPath", uploadArgument(upload),
                        "fileName", "说明.txt",
                        "title", "说明资料",
                        "category", "文档",
                        "tags", "说明",
                        "description", "测试资料",
                        "contentType", "text/plain"
                )
        ), runtime);
        return text(nested(imported.output(), "item").get("id"));
    }

    private Path stageUpload(String extension, byte[] content) throws IOException {
        Path upload = workspace.resolve(
                "modules/module_materials/uploads/" + UUID.randomUUID() + "." + extension
        );
        Files.createDirectories(upload.getParent());
        Files.write(upload, content);
        return upload;
    }

    private String relativeWorkspace(Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
    }

    private String uploadArgument(Path path) {
        String relative = relativeWorkspace(path);
        String prefix = "modules/module_materials/";
        if (!relative.startsWith(prefix)) {
            throw new IllegalArgumentException("测试上传路径不在模块目录内");
        }
        return relative.substring(prefix.length());
    }

    private ModuleInvocation invocation(String actionId, Map<String, Object> arguments) {
        return new ModuleInvocation(
                MaterialsModule.MODULE_ID,
                actionId,
                "plan-materials",
                "step-materials",
                arguments
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private ModuleRuntime newRuntime(Path workspacePath, Path trashPath) {
        Path normalizedWorkspace = workspacePath.toAbsolutePath().normalize();
        Path normalizedTrash = trashPath.toAbsolutePath().normalize();
        Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
        Map<String, TrashRecord> trash = new LinkedHashMap<>();
        ModuleRuntime mocked = mock(ModuleRuntime.class);

        when(mocked.workspaceLabel()).thenReturn("test");
        when(mocked.resolveWorkspacePath(anyString())).thenAnswer(invocation ->
                resolveWorkspace(normalizedWorkspace, invocation.getArgument(0))
        );
        when(mocked.exists(any(Path.class))).thenAnswer(invocation ->
                Files.exists(invocation.getArgument(0), LinkOption.NOFOLLOW_LINKS)
        );
        when(mocked.listDirectory(any(Path.class))).thenAnswer(invocation ->
                listFiles(
                        invocation.getArgument(0),
                        normalizedWorkspace,
                        normalizedTrash
                )
        );
        when(mocked.createDirectory(any(Path.class))).thenAnswer(invocation -> {
            try {
                return Files.createDirectories(invocation.getArgument(0));
            } catch (IOException exception) {
                throw new IllegalStateException("创建目录失败", exception);
            }
        });
        when(mocked.createTextFile(any(Path.class), anyString())).thenAnswer(invocation -> {
            Path file = invocation.getArgument(0);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, invocation.getArgument(1), StandardCharsets.UTF_8);
                return file;
            } catch (IOException exception) {
                throw new IllegalStateException("写入文本失败", exception);
            }
        });
        when(mocked.readTextFile(any(Path.class))).thenAnswer(invocation -> {
            try {
                return Files.readString(invocation.getArgument(0), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("读取文本失败", exception);
            }
        });
        doAnswer(invocation -> {
            moveFile(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mocked).move(any(Path.class), any(Path.class));
        when(mocked.moveToTrash(any(Path.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Path source = invocation.getArgument(0);
                    String id = UUID.randomUUID().toString();
                    Path stored = normalizedTrash.resolve(id).resolve(source.getFileName());
                    moveFile(source, stored);
                    TrashEntry entry = new TrashEntry(
                            id,
                            relativePath(normalizedWorkspace, normalizedTrash, source),
                            "trash/" + normalizedTrash.relativize(stored)
                                    .toString()
                                    .replace('\\', '/'),
                            source.getFileName().toString(),
                            Instant.now().toString()
                    );
                    trash.put(id, new TrashRecord(
                            entry,
                            source.toAbsolutePath().normalize(),
                            stored
                    ));
                    return entry;
                });
        when(mocked.restoreFromTrash(anyString())).thenAnswer(invocation -> {
            TrashRecord record = trash.get(invocation.getArgument(0));
            if (record == null) {
                throw new IllegalArgumentException("回收站条目不存在");
            }
            moveFile(record.stored(), record.original());
            trash.remove(invocation.getArgument(0));
            return record.original();
        });
        when(mocked.listTrash()).thenAnswer(invocation ->
                trash.values().stream().map(TrashRecord::entry).toList()
        );
        when(mocked.readItems(anyString())).thenAnswer(invocation ->
                collections.getOrDefault(invocation.getArgument(0), List.of()).stream()
                        .map(LinkedHashMap::new)
                        .map(Map::copyOf)
                        .toList()
        );
        when(mocked.hasItems(anyString())).thenAnswer(invocation ->
                collections.containsKey(invocation.getArgument(0))
        );
        doAnswer(invocation -> {
            List<Map<String, Object>> items = invocation.getArgument(1);
            collections.put(
                    invocation.getArgument(0),
                    items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList()
            );
            return null;
        }).when(mocked).writeItems(anyString(), anyList());
        when(mocked.newId()).thenAnswer(invocation -> UUID.randomUUID().toString());
        when(mocked.relativePath(any(Path.class))).thenAnswer(invocation ->
                relativePath(
                        normalizedWorkspace,
                        normalizedTrash,
                        invocation.getArgument(0)
                )
        );
        return mocked;
    }

    private Path resolveWorkspace(Path workspacePath, String relativePath) {
        Path resolved = workspacePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspacePath)) {
            throw new IllegalArgumentException("路径越过工作区");
        }
        return resolved;
    }

    private List<FileEntry> listFiles(
            Path directory,
            Path workspacePath,
            Path trashPath
    ) {
        try (var stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> {
                        try {
                            boolean isDirectory = Files.isDirectory(
                                    path,
                                    LinkOption.NOFOLLOW_LINKS
                            );
                            return new FileEntry(
                                    path.getFileName().toString(),
                                    relativePath(workspacePath, trashPath, path),
                                    isDirectory,
                                    isDirectory ? 0 : Files.size(path),
                                    Instant.ofEpochMilli(
                                            Files.getLastModifiedTime(path).toMillis()
                                    ).toString()
                            );
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取目录失败", exception);
        }
    }

    private void moveFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException exception) {
            throw new IllegalStateException("移动失败", exception);
        }
    }

    private String relativePath(Path workspacePath, Path trashPath, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspacePath)) {
            return workspacePath.relativize(normalized).toString().replace('\\', '/');
        }
        if (normalized.startsWith(trashPath)) {
            return "trash/" + trashPath.relativize(normalized)
                    .toString()
                    .replace('\\', '/');
        }
        throw new IllegalArgumentException("路径不在受控根内");
    }

    private record TrashRecord(TrashEntry entry, Path original, Path stored) {
    }
}
