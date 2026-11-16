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

/** 日历模块：日程、独立待办和模块自管空间。 */
public final class CalendarModule implements OrderlyModule {

    public static final String MODULE_ID = "module_calendar";
    private static final String EVENTS = "calendar";
    private static final String TODOS = "module_calendar_todos";
    private static final String SPACE_ROOT = "modules/module_calendar/spaces";
    private static final Set<String> SPACE_TYPES = Set.of("text", "image", "video");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SAFE_UPLOAD = Pattern.compile(
            "uploads/[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
    );
    private static final DateTimeFormatter LOCAL_INPUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

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

    private Map<String, Object> eventList(ModuleRuntime runtime) {
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(EVENTS));
        items.sort(Comparator.comparing(item -> text(item.get("startAt"))));
        return Map.of("items", items, "count", items.size());
    }

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

    private Map<String, Object> spaceList(ModuleRuntime runtime) {
        List<Map<String, Object>> items = spaceItems(runtime);
        return Map.of("items", items, "count", items.size());
    }

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

    private java.nio.file.Path spaceDirectory(ModuleRuntime runtime, String spaceId) {
        if (!SAFE_ID.matcher(spaceId).matches()) {
            throw new IllegalArgumentException("空间 ID 非法");
        }
        return runtime.resolveWorkspacePath(SPACE_ROOT + "/" + spaceId);
    }

    private int findById(List<Map<String, Object>> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(text(items.get(index).get("id")))) {
                return index;
            }
        }
        return -1;
    }

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

    private void requireEndAfterStart(String startAt, String endAt) {
        if (!OffsetDateTime.parse(endAt).isAfter(OffsetDateTime.parse(startAt))) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
    }

    private String normalizeDateTime(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isBlank()) {
            throw new IllegalArgumentException("时间不能为空");
        }
        try {
            OffsetDateTime.parse(input);
            return input;
        } catch (DateTimeParseException ignored) {
            try {
                LocalDateTime local = LocalDateTime.parse(input, LOCAL_INPUT);
                return local.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "时间必须为 ISO-8601 或 yyyy-MM-dd HH:mm：" + input,
                        exception
                );
            }
        }
    }

    private String required(ModuleInvocation invocation, String key) {
        String value = optional(invocation, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value;
    }

    private String optional(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    private String nestedText(Map<String, Object> source, String objectKey, String valueKey) {
        Object nested = source.get(objectKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(valueKey);
        return value == null ? null : value.toString();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
    }

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
