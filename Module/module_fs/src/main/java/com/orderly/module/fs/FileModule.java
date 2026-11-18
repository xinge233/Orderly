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
 * 文件与目录模块（{@code module_fs}）。
 *
 * <p>模块自包含沙箱文件查询、目录创建、移动、回收站恢复、快照和逆操作，只通过
 * {@link ModuleRuntime} 解析和操作应用自管工作区。查询与 {@code fs.list} 属于只读风险；
 * {@code fs.mkdir}、{@code fs.move} 和 {@code fs.restore} 属于写入风险；
 * {@code fs.trash} 属于删除风险，只能把对象移入项目回收站，绝不执行物理删除。</p>
 *
 * <p>宿主统一担保风险授权、当前策略下的确认、模块启用、参数校验、快照持久化、审计、失败
 * 停止和逆序回滚；模块不直接打开数据库、不访问任意个人目录，也不自行判断调用来源。</p>
 */
public final class FileModule implements OrderlyModule {

    public static final String MODULE_ID = "module_fs";
    /** 标记本模块是否已完成工作区种子初始化，属于本模块自有状态。 */
    private static final String INITIALIZATION_STATE = "module_fs_initialized";
    /** 本模块独占的 JAR 内只读资料目录。 */
    private final StudyMaterialsCatalog materialsCatalog = new StudyMaterialsCatalog();

    /** 返回与模块契约一致的稳定模块标识。 */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 为空的受控工作区写入演示目录和文件，并记录初始化标记。
     *
     * <p>前置条件是宿主已创建受控运行时。本方法只在自有初始化集合尚不存在时运行；它仅在
     * 工作区根目录为空时创建固定示例，随后原子写入本模块标记集合。重复调用不会覆盖用户文件
     * 或重复追加种子，因此满足幂等初始化要求。</p>
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

    /**
     * 创建工作区演示内容。
     *
     * <p>只允许在根目录为空的初始化路径调用。目录和文件均经 {@link ModuleRuntime} 创建，
     * 且各自在不存在时才写入，避免覆盖已有对象。</p>
     */
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

    /**
     * 执行契约声明的只读文件查询。
     *
     * <p>目录查询只读取应用工作区；{@code fs.materials} 只读取本模块 JAR 内资料；
     * {@code fs.trash} 只读取宿主回收站清单。所有分支均不写文件、集合、设置或审计状态。
     * 查询 ID 和参数由宿主按契约预先校验。</p>
     */
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

    /**
     * 为文件动作记录执行前的最小恢复信息。
     *
     * <p>快照只保存调用参数与必要存在性，不执行目录创建、移动或回收站操作。创建目录依赖
     * 执行前是否存在；移动记录源与目标；{@code fs.trash} 回滚依赖正向输出
     * {@code trashEntry.id}；{@code fs.restore} 回滚依赖正向输出 {@code path}，快照中的
     * {@code trashId} 是原始调用参数。返回值只含可 JSON 化标量，可重复准备且不改变业务状态。</p>
     */
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

    /**
     * 分派并执行一个已授权的文件 Action。
     *
     * <p>宿主必须在调用前完成启用、参数和当前风险策略下的授权与确认校验。读取分支不写状态；
     * 写入分支只通过 {@link ModuleRuntime} 在自管工作区内创建或移动；删除分支只进入项目
     * 回收站。模块不自行去重，宿主必须保证同一执行步骤至多进入一次。</p>
     */
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

    /**
     * 根据快照和正向输出撤销一个已完成的文件动作。
     *
     * <p>目录创建通过把新目录移入回收站撤销；移动把目标移回源位置；删除从项目回收站恢复；
     * 恢复操作再次把恢复对象移入回收站。只读动作无需回滚。目录和移动分支先检查当前路径状态，
     * 已在目标状态时不重复变更；回收站分支依赖正向输出中的条目标识，由宿主回收站规则裁决
     * 恢复结果。宿主只对已完成动作调用回滚并记录结果。</p>
     */
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

    /** 执行只读目录列举动作，返回工作区相对路径和摘要条目。 */
    private ModuleResult list(ModuleInvocation invocation, ModuleRuntime runtime) {
        String path = optionalStringArgument(invocation, "path", "");
        List<FileEntry> entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        return new ModuleResult("已列出 " + entries.size() + " 个项目", Map.of("path", path, "entries", entries));
    }

    /**
     * 供查询接口列举目录。
     *
     * <p>空白路径视为工作区根目录，真实边界解析由运行时完成，模块不自行拼接或规范化绝对路径。
     * </p>
     */
    private Map<String, Object> listQuery(String rawPath, ModuleRuntime runtime) {
        String path = Objects.requireNonNullElse(rawPath, "").trim();
        List<FileEntry> entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        return Map.of("path", path, "entries", entries, "count", entries.size());
    }

    /** 在自管工作区创建目录，返回运行时确认后的相对路径。 */
    private ModuleResult mkdir(ModuleInvocation invocation, ModuleRuntime runtime) {
        String path = stringArgument(invocation, "path");
        Path target = runtime.createDirectory(runtime.resolveWorkspacePath(path));
        return new ModuleResult("已创建目录：" + path, Map.of("path", runtime.relativePath(target)));
    }

    /**
     * 在工作区内移动或重命名对象。
     *
     * <p>源和目标都交给运行时解析，目标已存在时由宿主拒绝覆盖；本方法不绕过运行时访问真实
     * 文件系统。</p>
     */
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

    /**
     * 把工作区对象移入项目回收站。
     *
     * <p>计划 ID 与步骤 ID 由宿主签发的调用提供，用于生成可追溯的回收站条目；不执行物理删除。
     * </p>
     */
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

    /** 按回收站条目 ID 恢复对象，目标冲突由宿主拒绝覆盖。 */
    private ModuleResult restore(ModuleInvocation invocation, ModuleRuntime runtime) {
        String trashId = stringArgument(invocation, "trashId");
        Path restored = runtime.restoreFromTrash(trashId);
        return new ModuleResult(
                "已从回收站恢复：" + runtime.relativePath(restored),
                Map.of("path", runtime.relativePath(restored))
        );
    }

    /**
     * 撤销目录创建。
     *
     * <p>执行前目录已存在时保持原状；目录已消失时直接成功以保持幂等；否则把本次新建目录移入
     * 回收站，而不是物理删除。</p>
     */
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

    /**
     * 把移动后的目标移回原位置。
     *
     * <p>源仍在且目标不存在说明移动未发生，直接保持幂等；目标已消失时不猜测用户后续变更，
     * 只返回审计提示；目标存在时通过宿主运行时执行无覆盖的逆移动。</p>
     */
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

    /**
     * 从正向输出提取回收站 ID 并恢复被删除对象。
     *
     * <p>缺少回收站标识时拒绝回滚，防止在不明确对象的情况下猜测恢复目标。</p>
     */
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

    /**
     * 撤销从回收站恢复的操作。
     *
     * <p>恢复后的路径由正向输出提供；对象已消失时直接成功以保持幂等，否则再次移入项目回收站
     * 并保留回滚审计。</p>
     */
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

    /** 取得必填字符串参数，并裁剪首尾空白供运行时解析。 */
    private String stringArgument(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    /** 取得可选字符串参数；缺省时返回给定回退值。 */
    private String optionalStringArgument(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    /** 仅在目录不存在时创建种子目录，避免覆盖用户对象。 */
    private void seedDirectory(ModuleRuntime runtime, String path) {
        Path directory = runtime.resolveWorkspacePath(path);
        if (!runtime.exists(directory)) {
            runtime.createDirectory(directory);
        }
    }

    /** 仅在文件不存在时创建种子文本，避免覆盖用户内容。 */
    private void seedFile(ModuleRuntime runtime, String path, String content) {
        Path file = runtime.resolveWorkspacePath(path);
        if (!runtime.exists(file)) {
            runtime.createTextFile(file, content);
        }
    }
}
