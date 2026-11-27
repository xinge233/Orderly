package com.orderly.module.materials;

import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.TrashEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地资料管理模块（{@code module_materials}）。
 *
 * <p>模块保存资料元数据与导入文件的一一对应关系。文件只存放在应用自管工作区
 * {@code modules/module_materials/} 内；删除和失败补偿只通过宿主回收站与快照完成。
 * 授权、确认、审计、执行顺序和反向回滚仍由宿主担保。</p>
 */
public final class MaterialsModule implements OrderlyModule {

    public static final String MODULE_ID = "module_materials";
    static final String ITEMS = "module_materials_items";

    private static final String MODULE_ROOT = "modules/" + MODULE_ID;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern SAFE_UPLOAD = Pattern.compile(
            "uploads/[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
    );
    private static final Set<String> FORMATS = Set.of(
            "pdf", "docx", "xlsx", "pptx",
            "txt", "md", "csv", "json",
            "png", "jpg", "jpeg", "webp", "mp4", "webm"
    );
    private static final int MAX_TITLE = 200;
    private static final int MAX_CATEGORY = 80;
    private static final int MAX_FILE_NAME = 255;
    private static final int MAX_DESCRIPTION = 2_000;
    private static final int MAX_TAGS = 12;
    private static final int MAX_TAG_LENGTH = 40;

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 建立空的资料集合。只在集合文件尚不存在时写入，重复启动不会覆盖已有资料，
     * 也不会注入伪资料。
     */
    @Override
    public void initialize(ModuleRuntime runtime) {
        if (!runtime.hasItems(ITEMS)) {
            runtime.writeItems(ITEMS, List.of());
        }
    }

    /**
     * 执行只读资料查询。查询只返回元数据，不读取文件正文，也不产生集合或文件副作用。
     */
    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        List<Map<String, Object>> items = runtime.readItems(ITEMS);
        return switch (queryId) {
            case "materials.list" -> result("ACTIVE", items);
            case "materials.trash.list" -> result("TRASHED", items);
            default -> throw new IllegalArgumentException("资料模块未提供查询：" + queryId);
        };
    }

    /**
     * 为任意资料写入动作保存完整集合快照。文件状态由对应动作的逆操作单独处理。
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of("items", copy(runtime.readItems(ITEMS)));
    }

    /**
     * 按契约执行单个资料动作。宿主必须在调用前完成模块启用、参数、风险和确认校验。
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        requireModule(invocation);
        return switch (invocation.actionId()) {
            case "materials.import" -> importMaterial(invocation, runtime);
            case "materials.update" -> updateMaterial(invocation, runtime);
            case "materials.trash" -> trashMaterial(invocation, runtime);
            case "materials.restore" -> restoreMaterial(invocation, runtime);
            default -> throw new IllegalArgumentException("未知资料动作：" + invocation.actionId());
        };
    }

    /**
     * 根据正向动作执行幂等逆操作。导入回滚会回收已创建目录；删除回滚会恢复回收站对象；
     * 恢复回滚会再次把对象移入回收站。其余写入动作按执行前集合快照整体恢复。
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        requireModule(invocation);
        return switch (invocation.actionId()) {
            case "materials.import" -> rollbackImport(
                    invocation,
                    executionOutput,
                    castItems(snapshot.get("items")),
                    runtime
            );
            case "materials.trash" -> rollbackTrash(
                    executionOutput,
                    castItems(snapshot.get("items")),
                    runtime
            );
            case "materials.restore" -> rollbackRestore(
                    invocation,
                    executionOutput,
                    runtime
            );
            default -> {
                runtime.writeItems(ITEMS, castItems(snapshot.get("items")));
                yield ModuleResult.completed("已恢复资料变更前的元数据");
            }
        };
    }

    /** 导入一个已由宿主暂存的文件并创建资料记录。 */
    private ModuleResult importMaterial(ModuleInvocation invocation, ModuleRuntime runtime) {
        String uploadPath = required(invocation, "uploadPath");
        if (!SAFE_UPLOAD.matcher(uploadPath).matches()) {
            throw new IllegalArgumentException("上传路径非法");
        }
        String format = extension(uploadPath);
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("不支持的资料格式：" + format);
        }
        String title = bounded(required(invocation, "title"), "资料标题", MAX_TITLE);
        String fileName = safeFileName(required(invocation, "fileName"));
        String category = bounded(optional(invocation, "category"), "分类", MAX_CATEGORY);
        String description = bounded(
                optional(invocation, "description"),
                "资料说明",
                MAX_DESCRIPTION
        );
        List<String> tags = parseTags(optional(invocation, "tags"));
        String contentType = optional(invocation, "contentType");
        if (contentType.length() > 128) {
            throw new IllegalArgumentException("内容类型过长");
        }

        String id = runtime.newId();
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalStateException("宿主生成的资料 ID 非法");
        }
        var source = resolve(runtime, uploadPath);
        if (!runtime.exists(source)) {
            throw new IllegalArgumentException("上传文件不存在或已被清理");
        }
        var directory = itemDirectory(runtime, id);
        String storedName = "file." + format;
        var target = directory.resolve(storedName);

        try {
            runtime.createDirectory(directory);
            runtime.move(source, target);
            long size = sizeOf(runtime, directory, storedName);
            Instant now = Instant.now();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("title", title);
            item.put("description", description);
            item.put("category", category);
            item.put("tags", tags);
            item.put("fileName", fileName);
            item.put("format", format.toUpperCase(Locale.ROOT));
            item.put("sizeBytes", size);
            item.put("contentType", contentType.isBlank()
                    ? defaultContentType(format)
                    : contentType);
            item.put("storagePath", "files/" + id + "/" + storedName);
            item.put("status", "ACTIVE");
            item.put("createdAt", now.toString());
            item.put("updatedAt", now.toString());
            Map<String, Object> immutable = Map.copyOf(item);

            List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(ITEMS));
            items.add(immutable);
            runtime.writeItems(ITEMS, items);
            return new ModuleResult("已导入资料：" + title, Map.of("item", immutable));
        } catch (RuntimeException exception) {
            if (runtime.exists(directory)) {
                try {
                    runtime.moveToTrash(directory, invocation.planId(), invocation.stepId());
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    /** 修改资料展示信息，不改变文件内容或存储位置。 */
    private ModuleResult updateMaterial(ModuleInvocation invocation, ModuleRuntime runtime) {
        String id = safeId(required(invocation, "id"));
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(ITEMS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalArgumentException("未找到资料：" + id);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        if (!"ACTIVE".equals(text(before.get("status")))) {
            throw new IllegalArgumentException("只能编辑资料库中的有效资料");
        }
        Map<String, Object> after = new LinkedHashMap<>(before);
        boolean changed = false;
        if (invocation.arguments().containsKey("title")) {
            after.put("title", bounded(
                    required(invocation, "title"),
                    "资料标题",
                    MAX_TITLE
            ));
            changed = true;
        }
        if (invocation.arguments().containsKey("category")) {
            after.put("category", bounded(
                    optional(invocation, "category"),
                    "分类",
                    MAX_CATEGORY
            ));
            changed = true;
        }
        if (invocation.arguments().containsKey("tags")) {
            after.put("tags", parseTags(optional(invocation, "tags")));
            changed = true;
        }
        if (invocation.arguments().containsKey("description")) {
            after.put("description", bounded(
                    optional(invocation, "description"),
                    "资料说明",
                    MAX_DESCRIPTION
            ));
            changed = true;
        }
        if (!changed) {
            throw new IllegalArgumentException("至少提供一个需要修改的资料字段");
        }
        after.put("updatedAt", Instant.now().toString());
        Map<String, Object> immutableAfter = Map.copyOf(after);
        items.set(index, immutableAfter);
        runtime.writeItems(ITEMS, items);
        return new ModuleResult(
                "已更新资料：" + text(immutableAfter.get("title")),
                Map.of("before", Map.copyOf(before), "after", immutableAfter)
        );
    }

    /** 把资料目录整体移入项目回收站，并保留恢复所需元数据。 */
    private ModuleResult trashMaterial(ModuleInvocation invocation, ModuleRuntime runtime) {
        String id = safeId(required(invocation, "id"));
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(ITEMS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalArgumentException("未找到资料：" + id);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        if (!"ACTIVE".equals(text(before.get("status")))) {
            throw new IllegalArgumentException("资料已经在回收站中");
        }
        var directory = itemDirectory(runtime, id);
        if (!runtime.exists(directory)) {
            throw new IllegalStateException("资料文件目录不存在，无法安全移入回收站");
        }

        TrashEntry trash = runtime.moveToTrash(
                directory,
                invocation.planId(),
                invocation.stepId()
        );
        Instant now = Instant.now();
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", "TRASHED");
        after.put("trashId", trash.id());
        after.put("deletedAt", now.toString());
        after.put("updatedAt", now.toString());
        Map<String, Object> immutableAfter = Map.copyOf(after);
        items.set(index, immutableAfter);
        try {
            runtime.writeItems(ITEMS, items);
        } catch (RuntimeException exception) {
            try {
                runtime.restoreFromTrash(trash.id());
            } catch (RuntimeException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
        return new ModuleResult(
                "已将资料移入回收站：" + text(before.get("title")),
                Map.of("item", immutableAfter, "trashEntry", trash.asMap())
        );
    }

    /** 从项目回收站恢复资料目录，并把元数据重新标记为有效。 */
    private ModuleResult restoreMaterial(ModuleInvocation invocation, ModuleRuntime runtime) {
        String id = safeId(required(invocation, "id"));
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(ITEMS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalArgumentException("未找到资料：" + id);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        if (!"TRASHED".equals(text(before.get("status")))) {
            throw new IllegalArgumentException("资料不在回收站中");
        }
        String trashId = text(before.get("trashId"));
        if (!SAFE_ID.matcher(trashId).matches()) {
            throw new IllegalStateException("资料缺少有效回收站条目");
        }
        var directory = itemDirectory(runtime, id);
        if (!runtime.exists(directory)) {
            runtime.restoreFromTrash(trashId);
        }

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", "ACTIVE");
        after.remove("trashId");
        after.remove("deletedAt");
        after.put("updatedAt", Instant.now().toString());
        Map<String, Object> immutableAfter = Map.copyOf(after);
        items.set(index, immutableAfter);
        try {
            runtime.writeItems(ITEMS, items);
        } catch (RuntimeException exception) {
            compensateFailedRestore(
                    invocation,
                    items,
                    index,
                    directory,
                    runtime,
                    exception
            );
            throw exception;
        }
        return new ModuleResult(
                "已从回收站恢复资料：" + text(after.get("title")),
                Map.of("item", immutableAfter)
        );
    }

    /** 导入回滚只回收本动作新建目录，并恢复执行前元数据。 */
    private ModuleResult rollbackImport(
            ModuleInvocation invocation,
            Map<String, Object> output,
            List<Map<String, Object>> snapshotItems,
            ModuleRuntime runtime
    ) {
        Map<String, Object> item = nestedMap(output, "item");
        String id = text(item.get("id"));
        if (SAFE_ID.matcher(id).matches()) {
            var directory = itemDirectory(runtime, id);
            if (runtime.exists(directory)) {
                runtime.moveToTrash(directory, invocation.planId(), invocation.stepId());
            }
        }
        runtime.writeItems(ITEMS, snapshotItems);
        return ModuleResult.completed("已撤销资料导入");
    }

    /** 删除回滚优先恢复原回收站条目，再恢复执行前元数据。 */
    private ModuleResult rollbackTrash(
            Map<String, Object> output,
            List<Map<String, Object>> snapshotItems,
            ModuleRuntime runtime
    ) {
        Map<String, Object> item = nestedMap(output, "item");
        String id = text(item.get("id"));
        String trashId = text(nestedMap(output, "trashEntry").get("id"));
        if (SAFE_ID.matcher(id).matches()) {
            var directory = itemDirectory(runtime, id);
            if (!runtime.exists(directory) && SAFE_ID.matcher(trashId).matches()) {
                runtime.restoreFromTrash(trashId);
            }
        }
        runtime.writeItems(ITEMS, snapshotItems);
        return ModuleResult.completed("已撤销资料删除");
    }

    /**
     * 恢复动作的逆操作是再次移入回收站。原 trashId 已被宿主消费，因此生成新的条目，
     * 避免把过期 ID 写回元数据。
     */
    private ModuleResult rollbackRestore(
            ModuleInvocation invocation,
            Map<String, Object> output,
            ModuleRuntime runtime
    ) {
        Map<String, Object> item = nestedMap(output, "item");
        String id = text(item.get("id"));
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalStateException("恢复输出缺少资料 ID");
        }
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(ITEMS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalStateException("恢复资料元数据不存在");
        }
        Map<String, Object> current = new LinkedHashMap<>(items.get(index));
        if ("TRASHED".equals(text(current.get("status")))) {
            return ModuleResult.completed("资料已经处于回收站状态");
        }
        var directory = itemDirectory(runtime, id);
        if (!runtime.exists(directory)) {
            throw new IllegalStateException("已恢复资料目录不存在");
        }
        TrashEntry trash = runtime.moveToTrash(
                directory,
                invocation.planId(),
                invocation.stepId()
        );
        Instant now = Instant.now();
        current.put("status", "TRASHED");
        current.put("trashId", trash.id());
        current.put("deletedAt", now.toString());
        current.put("updatedAt", now.toString());
        items.set(index, Map.copyOf(current));
        runtime.writeItems(ITEMS, items);
        return ModuleResult.completed("已撤销资料恢复");
    }

    /** 元数据写入失败时尽力把已经恢复的目录重新移入回收站，并保留双重失败原因。 */
    private void compensateFailedRestore(
            ModuleInvocation invocation,
            List<Map<String, Object>> items,
            int index,
            java.nio.file.Path directory,
            ModuleRuntime runtime,
            RuntimeException original
    ) {
        if (!runtime.exists(directory)) {
            return;
        }
        try {
            TrashEntry replacement = runtime.moveToTrash(
                    directory,
                    invocation.planId(),
                    invocation.stepId()
            );
            Map<String, Object> item = new LinkedHashMap<>(items.get(index));
            item.put("status", "TRASHED");
            item.put("trashId", replacement.id());
            item.put("deletedAt", Instant.now().toString());
            item.put("updatedAt", Instant.now().toString());
            items.set(index, Map.copyOf(item));
            runtime.writeItems(ITEMS, items);
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
    }

    private Map<String, Object> result(String status, List<Map<String, Object>> items) {
        List<Map<String, Object>> visible = items.stream()
                .filter(item -> status.equals(text(item.get("status"))))
                .sorted(Comparator.comparing(
                        (Map<String, Object> item) -> text(item.get("updatedAt"))
                ).reversed())
                .map(MaterialsModule::immutableItem)
                .toList();
        return Map.of("items", visible, "count", visible.size());
    }

    private java.nio.file.Path resolve(ModuleRuntime runtime, String relativePath) {
        return runtime.resolveWorkspacePath(MODULE_ROOT + "/" + relativePath);
    }

    private java.nio.file.Path itemDirectory(ModuleRuntime runtime, String id) {
        return resolve(runtime, "files/" + safeId(id));
    }

    private long sizeOf(
            ModuleRuntime runtime,
            java.nio.file.Path directory,
            String storedName
    ) {
        return runtime.listDirectory(directory).stream()
                .filter(entry -> !entry.directory())
                .filter(entry -> storedName.equals(entry.name()))
                .mapToLong(FileEntry::size)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无法读取导入文件摘要"));
    }

    private int findById(List<Map<String, Object>> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(text(items.get(index).get("id")))) {
                return index;
            }
        }
        return -1;
    }

    private void requireModule(ModuleInvocation invocation) {
        if (!MODULE_ID.equals(invocation.moduleId())) {
            throw new IllegalArgumentException("模块身份与资料模块不一致");
        }
    }

    private String required(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    private String optional(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String safeId(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("资料 ID 非法");
        }
        return value;
    }

    private String safeFileName(String value) {
        String fileName = bounded(value, "文件名", MAX_FILE_NAME);
        if (fileName.contains("/")
                || fileName.contains("\\")
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        return fileName;
    }

    private String bounded(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        if (raw.length() > 1_000) {
            throw new IllegalArgumentException("标签文本不能超过 1000 个字符");
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String candidate : raw.split("[,，;；]")) {
            String tag = candidate.trim();
            if (tag.isBlank()) {
                continue;
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("单个标签不能超过 " + MAX_TAG_LENGTH + " 个字符");
            }
            tags.add(tag);
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("标签不能超过 " + MAX_TAGS + " 个");
        }
        return List.copyOf(tags);
    }

    private String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String defaultContentType(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt", "md", "csv" -> "text/plain";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(MaterialsModule::immutableItem).toList();
    }

    private static Map<String, Object> immutableItem(Map<String, Object> item) {
        return Map.copyOf(new LinkedHashMap<>(item));
    }

    private List<Map<String, Object>> castItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("资料快照缺少集合内容");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("资料快照条目格式非法");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, entry) -> item.put(String.valueOf(key), entry));
            items.add(Map.copyOf(item));
        }
        return List.copyOf(items);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
