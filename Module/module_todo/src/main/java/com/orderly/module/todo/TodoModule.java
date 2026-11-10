package com.orderly.module.todo;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 待办模块：只通过宿主状态设施读写模块自有集合。 */
public final class TodoModule implements OrderlyModule {

    public static final String MODULE_ID = "module_todo";
    private static final String COLLECTION = "todos";

    @Override
    public String moduleId() {
        return MODULE_ID;
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
        if (!"todo.create".equals(invocation.actionId())) {
            throw new IllegalArgumentException("未知待办动作：" + invocation.actionId());
        }
        String title = required(invocation, "title");
        String dueAt = optional(invocation, "dueAt", "");
        List<Map<String, Object>> items = new ArrayList<>(runtime.readItems(COLLECTION));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", runtime.newId());
        item.put("title", title);
        item.put("dueAt", dueAt);
        item.put("status", "OPEN");
        item.put("createdAt", Instant.now().toString());
        items.add(Map.copyOf(item));
        runtime.writeItems(COLLECTION, items);
        return new ModuleResult("已创建待办：" + title, Map.of("item", item));
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        if (!snapshot.containsKey("items")) {
            throw new IllegalStateException("待办快照缺少集合内容");
        }
        runtime.writeItems(COLLECTION, castItems(snapshot.get("items")));
        return ModuleResult.completed("已撤销待办创建");
    }

    private String required(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    private String optional(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
    }

    private List<Map<String, Object>> castItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("待办快照格式非法");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new IllegalStateException("待办快照条目格式非法");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
            result.add(Map.copyOf(item));
        }
        return result;
    }
}
