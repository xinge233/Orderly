package com.orderly.module.fs;

import com.orderly.kernel.AuthorizedFileRoot;
import com.orderly.kernel.FileEntry;
import com.orderly.kernel.FileReadResult;
import com.orderly.kernel.FileWriteResult;
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
 * 文件与目录模块（{@code module_fs}）。
 *
 * <p>模块声明并实现授权文件根查询、文本分页读取、默认应用打开、创建、覆盖写入、
 * 二进制上传、移动和项目回收站动作。所有真实路径、能力位和系统保护目录均由宿主
 * {@link ModuleRuntime} 校验；模块不自行判定授权、不直接访问文件系统。</p>
 *
 * <p>兼容旧版应用工作区动作：未提供 {@code rootId} 时继续在应用自管工作区执行，
 * 便于既有计划与测试保持可用。新界面始终显式传递授权根 ID。</p>
 */
public final class FileModule implements OrderlyModule {

    public static final String MODULE_ID = "module_fs";
    private static final String INITIALIZATION_STATE = "module_fs_initialized";
    private static final int DEFAULT_READ_CHARS = 3_000;
    private final StudyMaterialsCatalog materialsCatalog = new StudyMaterialsCatalog();

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 只在工作区根目录为空时写入固定示例；重复调用不会覆盖用户文件。
     */
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

    /** 执行契约声明的只读查询。 */
    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        return switch (queryId) {
            case "fs.roots" -> rootsPayload(runtime);
            case "fs.summary" -> listPayload("", "", runtime);
            case "fs.list" -> listPayload(
                    parameters.getOrDefault("rootId", ""),
                    parameters.getOrDefault("path", ""),
                    runtime
            );
            case "fs.readGrantedText" -> readGrantedTextPayload(parameters, runtime);
            case "fs.materials" -> materialsCatalog.load();
            case "fs.trash" -> {
                List<TrashEntry> entries = runtime.listTrash();
                yield Map.of("entries", entries, "count", entries.size());
            }
            default -> throw new IllegalArgumentException("文件模块未提供查询：" + queryId);
        };
    }

    /**
     * 收集执行前只读快照。覆盖写入的旧文件备份由执行阶段生成并写入输出，
     * 本方法不复制、移动或删除任何文件。
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        String rootId = optionalStringArgument(invocation, "rootId", "");
        switch (invocation.actionId()) {
            case "fs.mkdir" -> {
                String path = stringArgument(invocation, "path");
                snapshot.put("kind", "mkdir");
                snapshot.put("rootId", rootId);
                snapshot.put("path", path);
                snapshot.put("existed", runtime.exists(resolve(runtime, rootId, path)));
            }
            case "fs.write", "fs.upload" -> {
                String path = stringArgument(invocation, "path");
                snapshot.put("kind", invocation.actionId().substring(3));
                snapshot.put("rootId", rootId);
                snapshot.put("path", path);
                snapshot.put("existed", runtime.exists(resolve(runtime, rootId, path)));
            }
            case "fs.move" -> {
                snapshot.put("kind", "move");
                snapshot.put("sourceRootId", optionalStringArgument(invocation, "sourceRootId", ""));
                snapshot.put("source", stringArgument(invocation, "source"));
                snapshot.put("targetRootId", optionalStringArgument(invocation, "targetRootId", ""));
                snapshot.put("target", stringArgument(invocation, "target"));
            }
            case "fs.trash" -> {
                snapshot.put("kind", "trash");
                snapshot.put("rootId", rootId);
                snapshot.put("path", stringArgument(invocation, "path"));
            }
            case "fs.restore" -> {
                snapshot.put("kind", "restore");
                snapshot.put("trashId", stringArgument(invocation, "trashId"));
            }
            case "fs.open" -> snapshot.put("kind", "open");
            default -> throw new IllegalArgumentException("未知文件动作：" + invocation.actionId());
        }
        return Map.copyOf(snapshot);
    }

    /** 分派并执行一个已由宿主完成授权与确认校验的文件动作。 */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return switch (invocation.actionId()) {
            case "fs.mkdir" -> mkdir(invocation, runtime);
            case "fs.write" -> writeText(invocation, runtime);
            case "fs.upload" -> upload(invocation, runtime);
            case "fs.move" -> move(invocation, runtime);
            case "fs.trash" -> trash(invocation, runtime);
            case "fs.restore" -> restore(invocation, runtime);
            case "fs.open" -> open(invocation, runtime);
            default -> throw new IllegalArgumentException("未知文件动作：" + invocation.actionId());
        };
    }

    /** 根据只读快照和正向输出撤销一个已完成动作。 */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return switch (invocation.actionId()) {
            case "fs.mkdir" -> rollbackMkdir(snapshot, runtime);
            case "fs.write", "fs.upload" -> rollbackWrite(snapshot, executionOutput, runtime);
            case "fs.move" -> rollbackMove(snapshot, runtime);
            case "fs.trash" -> rollbackTrash(executionOutput, runtime);
            case "fs.restore" -> rollbackRestore(executionOutput, runtime);
            case "fs.open" -> ModuleResult.completed("打开动作无需文件回滚");
            default -> ModuleResult.completed("只读动作无需回滚");
        };
    }

    /** 返回不含绝对路径的授权根摘要，避免 AI Query 自动回传宿主机路径。 */
    private Map<String, Object> rootsPayload(ModuleRuntime runtime) {
        List<Map<String, Object>> roots = runtime.listFileRoots().stream()
                .map(this::rootSummary)
                .toList();
        return Map.of("roots", roots, "count", roots.size());
    }

    /** 列出工作区或授权根目录的直接子项。 */
    private Map<String, Object> listPayload(
            String rootId,
            String rawPath,
            ModuleRuntime runtime
    ) {
        String path = Objects.requireNonNullElse(rawPath, "").trim();
        List<FileEntry> entries;
        if (rootId == null || rootId.isBlank()) {
            entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        } else {
            entries = runtime.listAuthorizedDirectory(runtime.resolveAuthorizedPath(rootId, path));
        }
        return Map.of("rootId", rootId == null ? "" : rootId, "path", path,
                "entries", entries, "count", entries.size());
    }

    /** 凭本机用户签发的短时令牌分页读取文本，不向模型返回文件路径。 */
    private Map<String, Object> readGrantedTextPayload(
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        String token = parameters.getOrDefault("token", "");
        if (token.isBlank()) {
            throw new IllegalArgumentException("缺少读取令牌");
        }
        int offset = integerParameter(parameters, "offset", 0);
        int maxChars = integerParameter(parameters, "maxChars", DEFAULT_READ_CHARS);
        FileReadResult result = runtime.readGrantedText(token, offset, maxChars);
        return Map.of(
                "content", result.content(),
                "offset", result.offset(),
                "nextOffset", result.nextOffset(),
                "size", result.size(),
                "eof", result.eof(),
                "encoding", result.encoding()
        );
    }

    private ModuleResult mkdir(ModuleInvocation invocation, ModuleRuntime runtime) {
        String rootId = optionalStringArgument(invocation, "rootId", "");
        String path = stringArgument(invocation, "path");
        Path target = runtime.createDirectory(resolve(runtime, rootId, path));
        return new ModuleResult(
                "已创建目录：" + path,
                Map.of("rootId", rootId, "path", relativeToRoot(runtime, rootId, target))
        );
    }

    private ModuleResult writeText(ModuleInvocation invocation, ModuleRuntime runtime) {
        String rootId = optionalStringArgument(invocation, "rootId", "");
        String path = stringArgument(invocation, "path");
        String content = Objects.requireNonNullElse(invocation.arguments().get("content"), "").toString();
        boolean overwrite = booleanArgument(invocation, "overwrite", false);
        Path target = resolve(runtime, rootId, path);
        if (rootId.isBlank()) {
            if (overwrite) {
                throw new IllegalArgumentException("旧版工作区写入暂不支持覆盖");
            }
            Path created = runtime.createTextFile(target, content);
            return new ModuleResult(
                    "已写入工作区文件：" + path,
                    Map.of("rootId", "", "path", runtime.relativePath(created),
                            "size", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            );
        }
        FileWriteResult result = runtime.writeAuthorizedTextFile(
                target,
                content,
                overwrite,
                invocation.planId(),
                invocation.stepId()
        );
        Map<String, Object> output = writeOutput(rootId, result);
        return new ModuleResult("已写入文件：" + result.relativePath(), output);
    }

    private ModuleResult upload(ModuleInvocation invocation, ModuleRuntime runtime) {
        String rootId = optionalStringArgument(invocation, "rootId", "");
        if (rootId.isBlank()) {
            throw new IllegalArgumentException("上传动作必须显式选择授权文件根");
        }
        String path = stringArgument(invocation, "path");
        String uploadPath = stringArgument(invocation, "uploadPath");
        boolean overwrite = booleanArgument(invocation, "overwrite", false);
        Path staged = runtime.resolveWorkspacePath("modules/" + MODULE_ID + "/" + uploadPath);
        Path target = runtime.resolveAuthorizedPath(rootId, path);
        FileWriteResult result = runtime.writeAuthorizedBinaryFile(
                staged,
                target,
                overwrite,
                invocation.planId(),
                invocation.stepId()
        );
        return new ModuleResult("已上传文件：" + result.relativePath(), writeOutput(rootId, result));
    }

    private ModuleResult move(ModuleInvocation invocation, ModuleRuntime runtime) {
        String sourceRootId = optionalStringArgument(invocation, "sourceRootId", "");
        String targetRootId = optionalStringArgument(invocation, "targetRootId", "");
        String sourceText = stringArgument(invocation, "source");
        String targetText = stringArgument(invocation, "target");
        Path source = resolve(runtime, sourceRootId, sourceText);
        Path target = resolve(runtime, targetRootId, targetText);
        runtime.move(source, target);
        return new ModuleResult(
                "已移动：" + sourceText + " → " + targetText,
                Map.of(
                        "sourceRootId", sourceRootId,
                        "source", sourceText,
                        "targetRootId", targetRootId,
                        "target", targetText
                )
        );
    }

    private ModuleResult trash(ModuleInvocation invocation, ModuleRuntime runtime) {
        String rootId = optionalStringArgument(invocation, "rootId", "");
        String path = stringArgument(invocation, "path");
        TrashEntry entry = runtime.moveToTrash(
                resolve(runtime, rootId, path),
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

    private ModuleResult open(ModuleInvocation invocation, ModuleRuntime runtime) {
        String rootId = optionalStringArgument(invocation, "rootId", "");
        String path = stringArgument(invocation, "path");
        runtime.openFile(resolve(runtime, rootId, path));
        return new ModuleResult(
                "已请求系统默认应用打开：" + path,
                Map.of("rootId", rootId, "path", path, "opened", true)
        );
    }

    private ModuleResult rollbackMkdir(Map<String, Object> snapshot, ModuleRuntime runtime) {
        if (Boolean.TRUE.equals(snapshot.get("existed"))) {
            return ModuleResult.completed("目录原本已存在，无需回滚");
        }
        String rootId = Objects.toString(snapshot.get("rootId"), "");
        String path = Objects.toString(snapshot.get("path"), "");
        Path target = resolve(runtime, rootId, path);
        if (!runtime.exists(target)) {
            return ModuleResult.completed("目录已不存在，回滚保持幂等");
        }
        TrashEntry entry = runtime.moveToTrashForRecovery(target, "rollback", "fs-mkdir");
        return new ModuleResult("已撤销目录创建：" + path, Map.of("trashEntry", entry.asMap()));
    }

    private ModuleResult rollbackWrite(
            Map<String, Object> snapshot,
            Map<String, Object> output,
            ModuleRuntime runtime
    ) {
        String rootId = Objects.toString(snapshot.get("rootId"), "");
        String path = Objects.toString(snapshot.get("path"), "");
        Path target = resolve(runtime, rootId, path);
        String backupId = Objects.toString(output.getOrDefault("backupId", ""), "");
        if (runtime.exists(target)) {
            runtime.moveToTrashForRecovery(target, "rollback", "fs-write");
        }
        if (!backupId.isBlank()) {
            Path restored = runtime.restoreFromTrash(backupId);
            return ModuleResult.completed("已恢复覆盖写入前的文件：" + runtime.relativePath(restored));
        }
        return ModuleResult.completed("已撤销新建文件：" + path);
    }

    private ModuleResult rollbackMove(Map<String, Object> snapshot, ModuleRuntime runtime) {
        String sourceRootId = Objects.toString(snapshot.get("sourceRootId"), "");
        String targetRootId = Objects.toString(snapshot.get("targetRootId"), "");
        Path source = resolve(runtime, sourceRootId, Objects.toString(snapshot.get("source"), ""));
        Path target = resolve(runtime, targetRootId, Objects.toString(snapshot.get("target"), ""));
        if (runtime.exists(source) && !runtime.exists(target)) {
            return ModuleResult.completed("对象仍在原位置，回滚保持幂等");
        }
        if (!runtime.exists(target)) {
            return ModuleResult.completed("移动目标已不存在；未执行二次变更，请检查审计记录");
        }
        runtime.move(target, source);
        return ModuleResult.completed("已撤销移动");
    }

    private ModuleResult rollbackTrash(Map<String, Object> executionOutput, ModuleRuntime runtime) {
        Object raw = executionOutput.get("trashEntry");
        if (!(raw instanceof Map<?, ?> map) || map.get("id") == null) {
            throw new IllegalStateException("删除输出缺少回收站条目，无法安全回滚");
        }
        Path restored = runtime.restoreFromTrash(map.get("id").toString());
        return ModuleResult.completed("已从回收站恢复：" + runtime.relativePath(restored));
    }

    private ModuleResult rollbackRestore(Map<String, Object> executionOutput, ModuleRuntime runtime) {
        String path = Objects.toString(executionOutput.get("path"), "");
        if (path.isBlank()) {
            throw new IllegalStateException("恢复输出缺少路径，无法回滚");
        }
        if (path.contains(":")) {
            String[] parts = path.split(":", 2);
            Path target = resolve(runtime, parts[0], parts[1]);
            if (!runtime.exists(target)) {
                return ModuleResult.completed("恢复对象已不存在，回滚保持幂等");
            }
            runtime.moveToTrashForRecovery(target, "rollback", "fs-restore");
        } else {
            Path target = runtime.resolveWorkspacePath(path);
            if (!runtime.exists(target)) {
                return ModuleResult.completed("恢复对象已不存在，回滚保持幂等");
            }
            runtime.moveToTrashForRecovery(target, "rollback", "fs-restore");
        }
        return ModuleResult.completed("已撤销恢复：" + path);
    }

    /** 解析工作区或显式授权根路径。 */
    private Path resolve(ModuleRuntime runtime, String rootId, String path) {
        return rootId == null || rootId.isBlank()
                ? runtime.resolveWorkspacePath(path)
                : runtime.resolveAuthorizedPath(rootId, path);
    }

    /** 获取执行后根内相对路径。 */
    private String relativeToRoot(ModuleRuntime runtime, String rootId, Path path) {
        if (rootId == null || rootId.isBlank()) {
            return runtime.relativePath(path);
        }
        Path root = runtime.resolveAuthorizedPath(rootId, "");
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Map<String, Object> writeOutput(String rootId, FileWriteResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rootId", rootId);
        output.put("path", result.relativePath());
        output.put("size", result.size());
        if (result.backup() != null) {
            output.put("backupId", result.backup().id());
        }
        return Map.copyOf(output);
    }

    private Map<String, Object> rootSummary(AuthorizedFileRoot root) {
        return Map.of(
                "id", root.id(),
                "label", root.label(),
                "read", root.read(),
                "write", root.write(),
                "delete", root.delete()
        );
    }

    private int integerParameter(Map<String, String> parameters, String key, int fallback) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("参数不是有效整数：" + key);
        }
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

    private boolean booleanArgument(ModuleInvocation invocation, String key, boolean fallback) {
        Object value = invocation.arguments().get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
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
