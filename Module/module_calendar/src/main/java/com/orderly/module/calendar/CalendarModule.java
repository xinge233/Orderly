package com.orderly.module.calendar;

import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.TrashEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 日历模块（{@code module_calendar}）。
 *
 * <p>模块自包含日程、日历待办和文本/图片/视频空间，只通过 {@link ModuleRuntime} 读写本模块
 * 自有集合与 {@code modules/module_calendar/spaces} 目录，不访问其他模块数据或任意个人目录。
 * 查询属于只读风险；日程与待办变更属于写入风险；空间删除属于删除风险，只能把整个空间目录
 * 移入项目回收站，禁止物理删除。</p>
 *
 * <p>宿主统一担保模块启用、参数与风险校验、当前策略下的确认、快照持久化、审计、执行顺序和
 * 失败回滚。模块不自行判断授权或调用来源，也不宣称已将空间上传或 AI 计划发送到远端。</p>
 */
public final class CalendarModule implements OrderlyModule {

    public static final String MODULE_ID = "module_calendar";
    /** 仅属于本模块的日程运行态集合。 */
    private static final String EVENTS = "calendar";
    /** 日历模块独立待办集合，不与待办模块共享。 */
    private static final String TODOS = "module_calendar_todos";
    /** 本模块独占的空间元数据与媒体工作区根目录。 */
    private static final String SPACE_ROOT = "modules/module_calendar/spaces";
    /** 模块当前实际支持的空间类型。 */
    private static final Set<String> SPACE_TYPES = Set.of("text", "image", "video");
    /** 限制空间 ID 只能作为单一路径段使用。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** 限制宿主临时上传路径，避免跨出本模块上传目录。 */
    private static final Pattern SAFE_UPLOAD = Pattern.compile(
            "uploads/[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
    );
    private static final DateTimeFormatter LOCAL_INPUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 返回与模块契约一致的稳定模块标识。
     *
     * <p>该值不得随启用状态、用户设置或空间数据变化。</p>
     */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 幂等初始化本模块的日程和独立待办集合。
     *
     * <p>前置条件是宿主已交付受控运行时。每个集合只在 {@code hasItems} 为 false 时写入，
     * 因而不会覆盖已创建的空集合或现有用户数据；重复调用不会重复追加种子。初始化只写两个
     * 自有集合，不创建空间或访问其他模块状态。</p>
     */
    @Override
    public void initialize(ModuleRuntime runtime) {
        if (!runtime.hasItems(EVENTS)) {
            runtime.writeItems(EVENTS, List.of(Map.of(
                    "id", "calendar-seed-1",
                    "title", "项目评审",
                    "startAt", "2026-11-18T10:00:00+08:00",
                    "endAt", "2026-11-18T11:00:00+08:00",
                    "status", "SCHEDULED",
                    "createdAt", "2026-11-10T09:00:00+08:00",
                    "updatedAt", "2026-11-10T09:00:00+08:00"
            )));
        }
        if (!runtime.hasItems(TODOS)) {
            runtime.writeItems(TODOS, List.of(Map.of(
                    "id", "calendar-todo-seed-1",
                    "title", "完成季度复盘",
                    "dueAt", "2026-11-20T18:00:00+08:00",
                    "status", "OPEN",
                    "category", "工作",
                    "annotation", "整理本季度关键结果与下季度重点。",
                    "createdAt", "2026-11-10T09:00:00+08:00",
                    "updatedAt", "2026-11-10T09:00:00+08:00"
            )));
        }
    }

    /**
     * 执行契约声明的只读查询。
     *
     * <p>日程、待办、空间和合并议程均只读取本模块自有数据；筛选参数不写回集合。空间查询会读取
     * 本模块空间目录中的元数据，但不修改文件。查询 ID 和参数由宿主按契约预先校验。</p>
     */
    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        return switch (queryId) {
            case "calendar.list" -> eventList(runtime);
            case "calendar.todo.list" -> todoList(parameters, runtime);
            case "calendar.space.list" -> spaceList(runtime);
            case "calendar.agenda" -> agenda(parameters, runtime);
            default -> throw new IllegalArgumentException("日程模块未提供查询：" + queryId);
        };
    }

    /**
     * 为即将执行的日程、待办或空间动作复制执行前状态。
     *
     * <p>日程动作快照日程集合；日历待办动作快照独立待办集合；空间动作快照从空间目录读取的
     * 元数据。方法只读且返回可 JSON 化内容，重复调用不会改变业务状态。空间快照不复制媒体
     * 正文；空间逆操作分别使用正向输出中的空间 ID 或回收站 ID 定位对象。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        if (invocation.actionId().startsWith("calendar.todo.")) {
            return Map.of("items", copy(runtime.readItems(TODOS)));
        }
        if (invocation.actionId().startsWith("calendar.space.")) {
            return Map.of("spaces", copy(spaceItems(runtime)));
        }
        return Map.of("items", copy(runtime.readItems(EVENTS)));
    }

    /**
     * 分派并执行一个已授权的日程模块 Action。
     *
     * <p>宿主必须在调用前完成启用、参数和当前风险策略下的授权与确认校验，并保证同一执行步骤
     * 至多进入一次。日程与待办动作写入各自集合；空间创建只写本模块空间根目录；空间删除只通过
     * {@link ModuleRuntime#moveToTrash} 进入项目回收站。模块不自行判定授权或执行跨模块调用。</p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return switch (invocation.actionId()) {
            case "calendar.event.create" -> createEvent(invocation, runtime);
            case "calendar.event.reschedule", "calendar.reschedule" ->
                    rescheduleEvent(invocation, runtime);
            case "calendar.event.cancel" -> cancelEvent(invocation, runtime);
            case "calendar.todo.create" -> createTodo(invocation, runtime);
            case "calendar.todo.update" -> updateTodo(invocation, runtime);
            case "calendar.todo.complete" -> changeTodoStatus(invocation, runtime, "COMPLETED");
            case "calendar.todo.reopen" -> changeTodoStatus(invocation, runtime, "OPEN");
            case "calendar.space.create" -> createSpace(invocation, runtime);
            case "calendar.space.delete" -> deleteSpace(invocation, runtime);
            default -> throw new IllegalArgumentException("未知日程动作：" + invocation.actionId());
        };
    }

    /**
     * 根据动作类型撤销已完成的正向操作。
     *
     * <p>日程与待办通过快照整体覆盖对应集合；空间创建在目录仍存在时移入回收站，目录已不存在
     * 时直接成功以保持幂等；空间删除按正向输出中的回收站 ID 恢复。回滚应避免重复制造业务
     * 变更，回收站条目缺失或重复恢复由宿主能力裁决并记录审计。</p>
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        if (invocation.actionId().equals("calendar.space.create")) {
            String spaceId = nestedText(executionOutput, "space", "id");
            if (spaceId != null) {
                var directory = spaceDirectory(runtime, spaceId);
                if (runtime.exists(directory)) {
                    runtime.moveToTrash(directory, invocation.planId(), invocation.stepId());
                }
            }
            return ModuleResult.completed("已撤销空间创建");
        }
        if (invocation.actionId().equals("calendar.space.delete")) {
            String trashId = text(executionOutput.get("trashId"));
            if (!trashId.isBlank()) {
                runtime.restoreFromTrash(trashId);
            }
            return ModuleResult.completed("已恢复空间");
        }
        if (invocation.actionId().startsWith("calendar.todo.")) {
            runtime.writeItems(TODOS, castItems(snapshot.get("items")));
            return ModuleResult.completed("已撤销待办变更");
        }
        runtime.writeItems(EVENTS, castItems(snapshot.get("items")));
        return ModuleResult.completed("已撤销日程变更");
    }

    /**
     * 返回按开始时间升序排列的日程及总数。
     *
     * <p>排序基于集合副本，不修改运行态；空白或可解析时间由数据写入和规范化流程保证。</p>
     */
    private Map<String, Object> eventList(ModuleRuntime runtime) {
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(EVENTS));
        items.sort(Comparator.comparing(item -> text(item.get("startAt"))));
        return Map.of("items", items, "count", items.size());
    }

    /**
     * 按状态、分类、关键词和截止时间范围筛选日历待办。
     *
     * <p>状态匹配忽略大小写，分类要求完全相同，关键词匹配标题或批注并忽略大小写；时间范围
     * 按闭区间解释。所有操作只基于运行时返回副本，排序不会写回集合。</p>
     */
    private Map<String, Object> todoList(
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        String status = parameters.getOrDefault("status", "").trim();
        String category = parameters.getOrDefault("category", "").trim();
        String keyword = parameters.getOrDefault("keyword", "").trim().toLowerCase();
        String from = parameters.getOrDefault("from", "").trim();
        String to = parameters.getOrDefault("to", "").trim();
        List<Map<String, Object>> items = runtime.readItems(TODOS).stream()
                .filter(item -> status.isBlank() || status.equalsIgnoreCase(text(item.get("status"))))
                .filter(item -> category.isBlank() || category.equals(text(item.get("category"))))
                .filter(item -> keyword.isBlank()
                        || text(item.get("title")).toLowerCase().contains(keyword)
                        || text(item.get("annotation")).toLowerCase().contains(keyword))
                .filter(item -> inRange(text(item.get("dueAt")), from, to))
                .sorted(Comparator.comparing(item -> text(item.get("dueAt"))))
                .toList();
        return Map.of("items", items, "count", items.size());
    }

    /**
     * 将日程与独立待办按时间合并为只读议程。
     *
     * <p>两类条目使用不同字段归一为 {@code kind}/{@code at}，但本方法不创建跨数据关联，
     * 也不修改源集合；结果按时间值升序排列。</p>
     */
    private Map<String, Object> agenda(
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        String from = parameters.getOrDefault("from", "").trim();
        String to = parameters.getOrDefault("to", "").trim();
        List<Map<String, Object>> items = new ArrayList<>();
        runtime.readItems(EVENTS).stream()
                .filter(item -> inRange(text(item.get("startAt")), from, to))
                .forEach(item -> items.add(Map.of(
                        "kind", "EVENT",
                        "id", text(item.get("id")),
                        "title", text(item.get("title")),
                        "at", text(item.get("startAt")),
                        "endAt", text(item.get("endAt")),
                        "status", text(item.get("status"))
                )));
        runtime.readItems(TODOS).stream()
                .filter(item -> !text(item.get("dueAt")).isBlank())
                .filter(item -> inRange(text(item.get("dueAt")), from, to))
                .forEach(item -> items.add(Map.of(
                        "kind", "TODO",
                        "id", text(item.get("id")),
                        "title", text(item.get("title")),
                        "at", text(item.get("dueAt")),
                        "category", text(item.get("category")),
                        "status", text(item.get("status"))
                )));
        items.sort(Comparator.comparing(item -> text(item.get("at"))));
        return Map.of("items", items, "count", items.size());
    }

    /** 返回本模块空间元数据列表及数量，不读取其他模块目录。 */
    private Map<String, Object> spaceList(ModuleRuntime runtime) {
        List<Map<String, Object>> items = spaceItems(runtime);
        return Map.of("items", items, "count", items.size());
    }

    /**
     * 扫描本模块空间根目录并读取合法空间元数据。
     *
     * <p>根目录不存在时返回空列表；只处理顶层目录，跳过不含合法元数据的条目，并按创建时间
     * 排序。方法不修改目录或元数据文件。</p>
     */
    private List<Map<String, Object>> spaceItems(ModuleRuntime runtime) {
        var root = runtime.resolveWorkspacePath(SPACE_ROOT);
        if (!runtime.exists(root)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (FileEntry entry : runtime.listDirectory(root)) {
            if (!entry.directory()) {
                continue;
            }
            Map<String, Object> space = readSpace(runtime, entry.name());
            if (space != null) {
                items.add(space);
            }
        }
        items.sort(Comparator.comparing(item -> text(item.get("createdAt"))));
        return items;
    }

    /**
     * 创建日程并追加到本模块自有集合。
     *
     * <p>开始时间必须可规范化；省略结束时间时默认一小时后。方法校验结束晚于开始后执行一次
     * 集合原子替换。授权和步骤去重由宿主按当前风险策略负责。</p>
     */
    private ModuleResult createEvent(ModuleInvocation invocation, ModuleRuntime runtime) {
        String title = required(invocation, "title");
        String startAt = normalizeDateTime(required(invocation, "startAt"));
        String endAt = optional(invocation, "endAt", "");
        endAt = endAt.isBlank()
                ? OffsetDateTime.parse(startAt).plusHours(1).toString()
                : normalizeDateTime(endAt);
        requireEndAfterStart(startAt, endAt);

        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(EVENTS));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", runtime.newId());
        event.put("title", title);
        event.put("startAt", startAt);
        event.put("endAt", endAt);
        event.put("status", "SCHEDULED");
        event.put("createdAt", Instant.now().toString());
        event.put("updatedAt", event.get("createdAt"));
        items.add(Map.copyOf(event));
        runtime.writeItems(EVENTS, items);
        return new ModuleResult("已创建日程：" + title, Map.of("event", event));
    }

    /**
     * 按稳定 ID 或唯一标题调整日程时间。
     *
     * <p>标题仅在调用方未提供 {@code eventId} 时使用，且必须唯一匹配。未提供新结束时间时
     * 保留原持续时长；原始结束时间不可用时默认一小时。写入会更新集合并保留前次开始时间供
     * 审计展示，但不自行判断授权。</p>
     */
    private ModuleResult rescheduleEvent(ModuleInvocation invocation, ModuleRuntime runtime) {
        String eventId = optional(invocation, "eventId", "");
        String title = optional(invocation, "title", "");
        String newStartAt = normalizeDateTime(required(invocation, "newStartAt"));
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(EVENTS));
        int index = eventId.isBlank() ? findByUniqueTitle(items, title) : findById(items, eventId);
        if (index < 0) {
            throw new IllegalArgumentException("未找到日程：" + (eventId.isBlank() ? title : eventId));
        }

        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        Map<String, Object> after = new LinkedHashMap<>(before);
        String oldStart = text(before.get("startAt"));
        String oldEnd = text(before.get("endAt"));
        String newEndAt = optional(invocation, "newEndAt", "");
        if (!newEndAt.isBlank()) {
            newEndAt = normalizeDateTime(newEndAt);
        } else if (!oldStart.isBlank() && !oldEnd.isBlank()) {
            var duration = java.time.Duration.between(
                    OffsetDateTime.parse(normalizeDateTime(oldStart)),
                    OffsetDateTime.parse(normalizeDateTime(oldEnd))
            );
            newEndAt = OffsetDateTime.parse(newStartAt).plus(duration).toString();
        } else {
            newEndAt = OffsetDateTime.parse(newStartAt).plusHours(1).toString();
        }
        requireEndAfterStart(newStartAt, newEndAt);
        after.put("previousStartAt", oldStart);
        after.put("startAt", newStartAt);
        after.put("endAt", newEndAt);
        after.put("updatedAt", Instant.now().toString());
        items.set(index, Map.copyOf(after));
        runtime.writeItems(EVENTS, items);
        return new ModuleResult(
                "已调整日程“" + text(after.get("title")) + "”的时间",
                Map.of("before", before, "after", after)
        );
    }

    /**
     * 将日程标记为已取消。
     *
     * <p>取消保留原记录和字段，只更新状态及修改时间，便于依靠快照回滚；不物理移除日程。</p>
     */
    private ModuleResult cancelEvent(ModuleInvocation invocation, ModuleRuntime runtime) {
        String eventId = required(invocation, "eventId");
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(EVENTS));
        int index = findById(items, eventId);
        if (index < 0) {
            throw new IllegalArgumentException("未找到日程：" + eventId);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", "CANCELLED");
        after.put("updatedAt", Instant.now().toString());
        items.set(index, Map.copyOf(after));
        runtime.writeItems(EVENTS, items);
        return new ModuleResult(
                "已取消日程：" + text(after.get("title")),
                Map.of("before", before, "after", after)
        );
    }

    /**
     * 在日历模块独立集合中创建待办。
     *
     * <p>标题必填，截止时间可选且非空时规范化；新条目通过框架 ID、开放状态和审计时间字段
     * 写入本模块集合，不读取待办模块的集合。</p>
     */
    private ModuleResult createTodo(ModuleInvocation invocation, ModuleRuntime runtime) {
        String title = required(invocation, "title");
        String dueAt = optional(invocation, "dueAt", "");
        dueAt = dueAt.isBlank() ? "" : normalizeDateTime(dueAt);
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(TODOS));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", runtime.newId());
        item.put("title", title);
        item.put("dueAt", dueAt);
        item.put("status", "OPEN");
        item.put("category", optional(invocation, "category", ""));
        item.put("annotation", optional(invocation, "annotation", ""));
        item.put("createdAt", Instant.now().toString());
        item.put("updatedAt", item.get("createdAt"));
        items.add(Map.copyOf(item));
        runtime.writeItems(TODOS, items);
        return new ModuleResult("已创建待办：" + title, Map.of("item", item));
    }

    /**
     * 部分更新日历待办的可编辑字段。
     *
     * <p>只有调用参数显式包含的字段才会修改，至少要提供一个可修改字段；空标题被拒绝，非空
     * 截止时间会规范化。更新保留未提供字段并刷新修改时间，最后原子替换自有集合。</p>
     */
    private ModuleResult updateTodo(ModuleInvocation invocation, ModuleRuntime runtime) {
        String id = required(invocation, "id");
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(TODOS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalArgumentException("未找到待办：" + id);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        Map<String, Object> after = new LinkedHashMap<>(before);
        boolean changed = false;
        for (String field : List.of("title", "dueAt", "category", "annotation")) {
            if (!invocation.arguments().containsKey(field)) {
                continue;
            }
            String value = optional(invocation, field, "").trim();
            if (field.equals("title") && value.isBlank()) {
                throw new IllegalArgumentException("待办标题不能为空");
            }
            if (field.equals("dueAt") && !value.isBlank()) {
                value = normalizeDateTime(value);
            }
            after.put(field, value);
            changed = true;
        }
        if (!changed) {
            throw new IllegalArgumentException("待办更新至少需要一个可修改字段");
        }
        after.put("updatedAt", Instant.now().toString());
        items.set(index, Map.copyOf(after));
        runtime.writeItems(TODOS, items);
        return new ModuleResult(
                "已更新待办：" + text(after.get("title")),
                Map.of("before", before, "after", after)
        );
    }

    /**
     * 将日历待办切换为完成或开放状态。
     *
     * <p>状态由动作路由固定，不接受调用参数覆盖；只修改目标条目的状态与更新时间，并返回
     * 修改前后副本供宿主审计。</p>
     */
    private ModuleResult changeTodoStatus(
            ModuleInvocation invocation,
            ModuleRuntime runtime,
            String status
    ) {
        String id = required(invocation, "id");
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(TODOS));
        int index = findById(items, id);
        if (index < 0) {
            throw new IllegalArgumentException("未找到待办：" + id);
        }
        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", status);
        after.put("updatedAt", Instant.now().toString());
        items.set(index, Map.copyOf(after));
        runtime.writeItems(TODOS, items);
        return new ModuleResult(
                ("COMPLETED".equals(status) ? "已完成待办：" : "已重开待办：")
                        + text(after.get("title")),
                Map.of("before", before, "after", after)
        );
    }

    /**
     * 在本模块空间根目录创建文本或媒体空间。
     *
     * <p>空间 ID 由框架生成且只能形成单一路径段；文本限制长度，媒体上传路径必须匹配本模块
     * 上传白名单并真实存在。元数据只写自有 {@code space.properties}；部分写入失败时把已创建
     * 的空间目录移入项目回收站后继续抛出原异常，不留下半完成的可展示空间。</p>
     */
    private ModuleResult createSpace(ModuleInvocation invocation, ModuleRuntime runtime) {
        String type = required(invocation, "type").toLowerCase();
        if (!SPACE_TYPES.contains(type)) {
            throw new IllegalArgumentException("空间类型仅支持 text、image 或 video");
        }
        String spaceId = runtime.newId();
        var directory = spaceDirectory(runtime, spaceId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", spaceId);
        values.put("type", type);
        values.put("createdAt", Instant.now().toString());
        try {
            runtime.createDirectory(directory);
            if ("text".equals(type)) {
                String text = required(invocation, "text");
                if (text.length() > 10_000) {
                    throw new IllegalArgumentException("文本空间不能超过 10000 字符");
                }
                values.put("text", text);
            } else {
                String uploadPath = required(invocation, "uploadPath");
                if (!SAFE_UPLOAD.matcher(uploadPath).matches()) {
                    throw new IllegalArgumentException("上传资源路径非法");
                }
                var source = runtime.resolveWorkspacePath(
                        "modules/module_calendar/" + uploadPath
                );
                if (!runtime.exists(source)) {
                    throw new IllegalArgumentException("上传资源不存在");
                }
                String extension = uploadPath.substring(uploadPath.lastIndexOf('.') + 1);
                String mediaName = "media." + extension;
                runtime.move(source, directory.resolve(mediaName));
                values.put("mediaPath", "spaces/" + spaceId + "/" + mediaName);
                values.put("contentType", required(invocation, "contentType"));
            }
            runtime.createTextFile(directory.resolve("space.properties"), properties(values));
            Map<String, Object> space = Map.copyOf(values);
            return new ModuleResult("已创建空间", Map.of("space", space));
        } catch (RuntimeException exception) {
            if (runtime.exists(directory)) {
                runtime.moveToTrash(directory, invocation.planId(), invocation.stepId());
            }
            throw exception;
        }
    }

    /**
     * 把整个自有空间目录移入项目回收站。
     *
     * <p>先校验安全 ID 并读取元数据确认空间属于本模块，再委托宿主使用当前计划与步骤标识执行
     * 回收站移动。该删除动作不执行物理删除，返回回收站 ID 供回滚恢复。</p>
     */
    private ModuleResult deleteSpace(ModuleInvocation invocation, ModuleRuntime runtime) {
        String spaceId = required(invocation, "spaceId");
        if (!SAFE_ID.matcher(spaceId).matches()) {
            throw new IllegalArgumentException("空间 ID 非法");
        }
        Map<String, Object> space = readSpace(runtime, spaceId);
        if (space == null) {
            throw new IllegalArgumentException("未找到空间：" + spaceId);
        }
        TrashEntry trash = runtime.moveToTrash(
                spaceDirectory(runtime, spaceId),
                invocation.planId(),
                invocation.stepId()
        );
        return new ModuleResult(
                "已将空间移入项目回收站",
                Map.of("space", space, "trashId", trash.id())
        );
    }

    /**
     * 从本模块空间目录读取单个空间元数据。
     *
     * <p>非法 ID 或缺少 {@code space.properties} 时返回 null，供列表跳过和删除动作报告不存在；
     * 文件读取失败则抛出明确异常，避免把损坏元数据伪装成空空间。</p>
     */
    private Map<String, Object> readSpace(ModuleRuntime runtime, String spaceId) {
        if (!SAFE_ID.matcher(spaceId).matches()) {
            return null;
        }
        var directory = spaceDirectory(runtime, spaceId);
        var file = directory.resolve("space.properties");
        if (!runtime.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(runtime.readTextFile(file)));
        } catch (IOException exception) {
            throw new IllegalStateException("读取空间元数据失败：" + spaceId, exception);
        }
        Map<String, Object> space = new LinkedHashMap<>();
        properties.forEach((key, value) -> space.put(String.valueOf(key), String.valueOf(value)));
        return Map.copyOf(space);
    }

    /** 把空间标量字段序列化为 Java properties 文本，不包含文件系统路径以外的业务状态。 */
    private String properties(Map<String, String> values) {
        Properties properties = new Properties();
        properties.putAll(values);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            properties.store(output, "Orderly calendar space");
            return output.toString(StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new IllegalStateException("序列化空间元数据失败", exception);
        }
    }

    /** 解析并校验本模块空间目录下的单一路径段，返回受控工作区路径。 */
    private java.nio.file.Path spaceDirectory(ModuleRuntime runtime, String spaceId) {
        if (!SAFE_ID.matcher(spaceId).matches()) {
            throw new IllegalArgumentException("空间 ID 非法");
        }
        return runtime.resolveWorkspacePath(SPACE_ROOT + "/" + spaceId);
    }

    /** 在集合中按稳定 ID 查找下标，未找到返回 -1。 */
    private int findById(List<Map<String, Object>> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(text(items.get(index).get("id")))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 按标题查找唯一下标。
     *
     * <p>标题为空、没有匹配或多个匹配时抛出明确异常，避免按标题修改时误选对象。</p>
     */
    private int findByUniqueTitle(List<Map<String, Object>> items, String title) {
        if (title.isBlank()) {
            throw new IllegalArgumentException("缺少参数：title 或 eventId");
        }
        int found = -1;
        for (int index = 0; index < items.size(); index++) {
            if (title.equals(text(items.get(index).get("title")))) {
                if (found >= 0) {
                    throw new IllegalArgumentException("存在多个同名日程，请使用 eventId：" + title);
                }
                found = index;
            }
        }
        return found;
    }

    /**
     * 判断时间值是否落在可选的闭区间内。
     *
     * <p>空白值在未提供起点时保留，提供起点时视为区间外；非空值统一规范化为带偏移时间后比较。
     * </p>
     */
    private boolean inRange(String value, String from, String to) {
        if (value == null || value.isBlank()) {
            return from == null || from.isBlank();
        }
        String normalized = normalizeDateTime(value);
        if (from != null && !from.isBlank()
                && OffsetDateTime.parse(normalized).isBefore(
                OffsetDateTime.parse(normalizeDateTime(from)))) {
            return false;
        }
        return to == null || to.isBlank()
                || !OffsetDateTime.parse(normalized).isAfter(
                OffsetDateTime.parse(normalizeDateTime(to)));
    }

    /** 校验结束时间严格晚于开始时间，避免写入无效日程区间。 */
    private void requireEndAfterStart(String startAt, String endAt) {
        if (!OffsetDateTime.parse(endAt).isAfter(OffsetDateTime.parse(startAt))) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
    }

    /**
     * 将调用参数中的时间统一为带系统时区偏移的 ISO-8601 字符串。
     *
     * <p>接受带偏移 ISO-8601、本地 ISO-8601 和 {@code yyyy-MM-dd HH:mm}；本地时间使用宿主
     * JVM 系统时区补全偏移。空值与所有不支持格式均被拒绝。</p>
     */
    private String normalizeDateTime(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isBlank()) {
            throw new IllegalArgumentException("时间不能为空");
        }
        try {
            OffsetDateTime.parse(input);
            return input;
        } catch (DateTimeParseException ignored) {
            for (DateTimeFormatter formatter : List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    LOCAL_INPUT
            )) {
                try {
                    LocalDateTime local = LocalDateTime.parse(input, formatter);
                    return local.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
                } catch (DateTimeParseException ignoredLocal) {
                    // 尝试下一种受支持的本地时间格式。
                }
            }
            throw new IllegalArgumentException(
                    "时间必须为带时区 ISO-8601、本地 ISO-8601 或 yyyy-MM-dd HH:mm：" + input
            );
        }
    }

    /** 取得必填字符串参数，空值拒绝，其余统一裁剪首尾空白。 */
    private String required(ModuleInvocation invocation, String key) {
        String value = optional(invocation, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value;
    }

    /** 取得可选字符串参数并统一裁剪首尾空白。 */
    private String optional(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    /** 从结构化正向输出中读取一层对象字段的文本，缺失时返回 null。 */
    private String nestedText(Map<String, Object> source, String objectKey, String valueKey) {
        Object nested = source.get(objectKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(valueKey);
        return value == null ? null : value.toString();
    }

    /** 将可空值转换为空字符串，供只读展示和排序使用。 */
    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 复制集合条目，避免快照映射与运行时返回对象共享可变包装。 */
    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
    }

    /**
     * 将宿主交回的快照集合严格转换回条目列表。
     *
     * <p>只接受列表和对象条目；结构不完整时拒绝覆盖集合，防止坏快照清空或破坏用户数据。</p>
     */
    private List<Map<String, Object>> castItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("快照集合格式非法");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new IllegalStateException("快照条目格式非法");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
            result.add(Map.copyOf(item));
        }
        return result;
    }
}
