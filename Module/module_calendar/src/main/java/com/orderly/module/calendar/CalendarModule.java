package com.orderly.module.calendar;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 日程模块：按标题或 ID 修改一条本地日程并保留前后版本。 */
public final class CalendarModule implements OrderlyModule {

    public static final String MODULE_ID = "module_calendar";
    private static final String COLLECTION = "calendar";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void initialize(ModuleRuntime runtime) {
        if (runtime.hasItems(COLLECTION)) {
            return;
        }
        runtime.writeItems(COLLECTION, List.of(Map.of(
                "id", "calendar-seed-1",
                "title", "项目评审",
                "startAt", "2026-11-18T10:00:00+08:00",
                "endAt", "2026-11-18T11:00:00+08:00"
        )));
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        if (!"calendar.list".equals(queryId)) {
            throw new IllegalArgumentException("日程模块未提供查询：" + queryId);
        }
        List<Map<String, Object>> items = runtime.readItems(COLLECTION);
        return Map.of("items", items, "count", items.size());
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of("items", copy(runtime.readItems(COLLECTION)));
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        if (!"calendar.reschedule".equals(invocation.actionId())) {
            throw new IllegalArgumentException("未知日程动作：" + invocation.actionId());
        }
        String title = required(invocation, "title");
        String newStartAt = required(invocation, "newStartAt");
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(COLLECTION));
        int index = find(items, title);
        if (index < 0) {
            throw new IllegalArgumentException("未找到日程：" + title);
        }

        Map<String, Object> before = new LinkedHashMap<>(items.get(index));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("previousStartAt", before.getOrDefault("startAt", ""));
        after.put("startAt", newStartAt);
        after.put("updatedAt", Instant.now().toString());
        items.set(index, Map.copyOf(after));
        runtime.writeItems(COLLECTION, items);
        return new ModuleResult(
                "已将日程“" + title + "”改到 " + newStartAt,
                Map.of("before", before, "after", after)
        );
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        if (!snapshot.containsKey("items")) {
            throw new IllegalStateException("日程快照缺少集合内容");
        }
        runtime.writeItems(COLLECTION, castItems(snapshot.get("items")));
        return ModuleResult.completed("已撤销日程改期");
    }

    private int find(List<Map<String, Object>> items, String title) {
        for (int i = 0; i < items.size(); i++) {
            if (title.equals(String.valueOf(items.get(i).get("title")))) {
                return i;
            }
        }
        return -1;
    }

    private String required(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
    }

    private List<Map<String, Object>> castItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("日程快照格式非法");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new IllegalStateException("日程快照条目格式非法");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
            result.add(Map.copyOf(item));
        }
        return result;
    }
}
