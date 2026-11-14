package com.orderly.module.fs;

import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.TrashEntry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件与目录模块。
 *
 * <p>模块不直接打开文件系统，而是通过宿主提供的 {@link ModuleRuntime} 完成
 * 受边界保护的路径解析、移动与回收站操作。</p>
 */
public final class FileModule implements OrderlyModule {

    public static final String MODULE_ID = "module_fs";
    private static final String INITIALIZATION_STATE = "module_fs_initialized";
    private final StudyMaterialsCatalog materialsCatalog = new StudyMaterialsCatalog();

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void initialize(ModuleRuntime runtime) {
        if (runtime.hasItems(INITIALIZATION_STATE)) {
            return;
        }
        if (runtime.listDirectory(runtime.resolveWorkspacePath("")).isEmpty()) {
            seedSamples(runtime);
        }
        runtime.writeItems(INITIALIZATION_STATE, List.of());
    }

    private void seedSamples(ModuleRuntime runtime) {
        seedDirectory(runtime, "收件箱");
        seedDirectory(runtime, "待整理");
        seedFile(
                runtime,
                "收件箱/示例笔记.txt",
                "这是归序本地演示工作区。\n可以安全地移动或删除后再回滚。\n"
        );
        seedFile(runtime, "待整理/草案.txt", "待整理草案\n");
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        return switch (queryId) {
            case "fs.list" -> listQuery(parameters.getOrDefault("path", ""), runtime);
            case "fs.summary" -> listQuery("", runtime);
            case "fs.materials" -> materialsCatalog.load();
            case "fs.trash" -> {
                List<TrashEntry> entries = runtime.listTrash();
                yield Map.of("entries", entries, "count", entries.size());
            }
            default -> throw new IllegalArgumentException("文件模块未提供查询：" + queryId);
        };
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        switch (invocation.actionId()) {
            case "fs.list" -> snapshot.put("kind", "read-only");
            case "fs.mkdir" -> {
                String path = stringArgument(invocation, "path");
                Path target = runtime.resolveWorkspacePath(path);
                snapshot.put("kind", "mkdir");
                snapshot.put("path", path);
                snapshot.put("existed", runtime.exists(target));
            }
            case "fs.move" -> {
                snapshot.put("kind", "move");
                snapshot.put("source", stringArgument(invocation, "source"));
                snapshot.put("target", stringArgument(invocation, "target"));
            }
            case "fs.trash" -> {
                snapshot.put("kind", "trash");
                snapshot.put("path", stringArgument(invocation, "path"));
            }
            case "fs.restore" -> {
                snapshot.put("kind", "restore");
                snapshot.put("trashId", stringArgument(invocation, "trashId"));
            }
            default -> throw new IllegalArgumentException("未知文件动作：" + invocation.actionId());
        }
        return Map.copyOf(snapshot);
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return switch (invocation.actionId()) {
            case "fs.list" -> list(invocation, runtime);
            case "fs.mkdir" -> mkdir(invocation, runtime);
            case "fs.move" -> move(invocation, runtime);
            case "fs.trash" -> trash(invocation, runtime);
            case "fs.restore" -> restore(invocation, runtime);
            default -> throw new IllegalArgumentException("未知文件动作：" + invocation.actionId());
        };
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return switch (invocation.actionId()) {
            case "fs.mkdir" -> rollbackMkdir(invocation, snapshot, runtime);
            case "fs.move" -> rollbackMove(invocation, snapshot, runtime);
            case "fs.trash" -> rollbackTrash(invocation, executionOutput, runtime);
            case "fs.restore" -> rollbackRestore(invocation, executionOutput, runtime);
            default -> ModuleResult.completed("只读动作无需回滚");
        };
    }

    private ModuleResult list(ModuleInvocation invocation, ModuleRuntime runtime) {
        String path = optionalStringArgument(invocation, "path", "");
        List<FileEntry> entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        return new ModuleResult("已列出 " + entries.size() + " 个项目", Map.of("path", path, "entries", entries));
    }

    private Map<String, Object> listQuery(String rawPath, ModuleRuntime runtime) {
        String path = Objects.requireNonNullElse(rawPath, "").trim();
        List<FileEntry> entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        return Map.of("path", path, "entries", entries, "count", entries.size());
    }

    private ModuleResult mkdir(ModuleInvocation invocation, ModuleRuntime runtime) {
        String path = stringArgument(invocation, "path");
        Path target = runtime.createDirectory(runtime.resolveWorkspacePath(path));
        return new ModuleResult("已创建目录：" + path, Map.of("path", runtime.relativePath(target)));
    }

    private ModuleResult move(ModuleInvocation invocation, ModuleRuntime runtime) {
        String sourceText = stringArgument(invocation, "source");
        String targetText = stringArgument(invocation, "target");
        Path source = runtime.resolveWorkspacePath(sourceText);
        Path target = runtime.resolveWorkspacePath(targetText);
        runtime.move(source, target);
        return new ModuleResult(
                "已移动：" + sourceText + " → " + targetText,
                Map.of("source", sourceText, "target", targetText)
        );
    }

    private ModuleResult trash(ModuleInvocation invocation, ModuleRuntime runtime) {
        String path = stringArgument(invocation, "path");
        TrashEntry entry = runtime.moveToTrash(
                runtime.resolveWorkspacePath(path),
                invocation.planId(),
                invocation.stepId()
        );
        return new ModuleResult(
                "已移入项目回收站：" + path,
                Map.of("trashEntry", entry.asMap())
        );
    }

    private ModuleResult restore(ModuleInvocation invocation, ModuleRuntime runtime) {
        String trashId = stringArgument(invocation, "trashId");
        Path restored = runtime.restoreFromTrash(trashId);
        return new ModuleResult(
                "已从回收站恢复：" + runtime.relativePath(restored),
                Map.of("path", runtime.relativePath(restored))
        );
    }

    private ModuleResult rollbackMkdir(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            ModuleRuntime runtime
    ) {
        if (Boolean.TRUE.equals(snapshot.get("existed"))) {
            return ModuleResult.completed("目录原本已存在，无需回滚");
        }
        Path target = runtime.resolveWorkspacePath(Objects.toString(snapshot.get("path")));
        if (!runtime.exists(target)) {
            return ModuleResult.completed("目录已不存在，回滚保持幂等");
        }
        TrashEntry entry = runtime.moveToTrash(
                target,
                invocation.planId(),
                "rollback-" + invocation.stepId()
        );
        return new ModuleResult(
                "已撤销目录创建：" + snapshot.get("path"),
                Map.of("trashEntry", entry.asMap())
        );
    }

    private ModuleResult rollbackMove(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            ModuleRuntime runtime
    ) {
        Path source = runtime.resolveWorkspacePath(Objects.toString(snapshot.get("source")));
        Path target = runtime.resolveWorkspacePath(Objects.toString(snapshot.get("target")));
        if (runtime.exists(source) && !runtime.exists(target)) {
            return ModuleResult.completed("文件仍在原位置，回滚保持幂等");
        }
        if (!runtime.exists(target)) {
            return ModuleResult.completed("移动目标已不存在；未执行二次变更，请检查审计记录");
        }
        runtime.move(target, source);
        return new ModuleResult("已撤销移动：" + snapshot.get("target") + " → " + snapshot.get("source"),
                Map.of("source", snapshot.get("target"), "target", snapshot.get("source")));
    }

    private ModuleResult rollbackTrash(
            ModuleInvocation invocation,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        Object raw = executionOutput.get("trashEntry");
        if (!(raw instanceof Map<?, ?> map) || map.get("id") == null) {
            throw new IllegalStateException("删除输出缺少回收站条目，无法安全回滚");
        }
        Path restored = runtime.restoreFromTrash(map.get("id").toString());
        return new ModuleResult("已从回收站恢复：" + runtime.relativePath(restored),
                Map.of("path", runtime.relativePath(restored)));
    }

    private ModuleResult rollbackRestore(
            ModuleInvocation invocation,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        String path = Objects.toString(executionOutput.get("path"), "");
        if (path.isBlank()) {
            throw new IllegalStateException("恢复输出缺少路径，无法回滚");
        }
        Path target = runtime.resolveWorkspacePath(path);
        if (!runtime.exists(target)) {
            return ModuleResult.completed("恢复对象已不存在，回滚保持幂等");
        }
        TrashEntry entry = runtime.moveToTrash(
                target,
                invocation.planId(),
                "rollback-" + invocation.stepId()
        );
        return new ModuleResult("已撤销恢复：" + path, Map.of("trashEntry", entry.asMap()));
    }

    private String stringArgument(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    private String optionalStringArgument(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    private void seedDirectory(ModuleRuntime runtime, String path) {
        Path directory = runtime.resolveWorkspacePath(path);
        if (!runtime.exists(directory)) {
            runtime.createDirectory(directory);
        }
    }

    private void seedFile(ModuleRuntime runtime, String path, String content) {
        Path file = runtime.resolveWorkspacePath(path);
        if (!runtime.exists(file)) {
            runtime.createTextFile(file, content);
        }
    }
}
