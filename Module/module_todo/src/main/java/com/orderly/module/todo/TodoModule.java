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

/**
 * 待办模块（{@code module_todo}）。
 *
 * <p>模块自包含待办查询、创建动作、快照和逆操作，只通过 {@link ModuleRuntime}
 * 读写自有 {@code todos} 集合，不访问其他模块状态或任意文件系统位置。查询属于只读风险；
 * 创建待办属于写入风险，由宿主按当前风险策略完成授权与确认并保留快照与审计。该模块不声明
 * 删除动作。</p>
 *
 * <p>授权、确认、调度顺序、快照持久化、失败处理和反向回滚均由宿主担保；模块只返回
 * 可 JSON 化的结构化结果，不自行判断调用来源或扩大权限。</p>
 */
public final class TodoModule implements OrderlyModule {

    public static final String MODULE_ID = "module_todo";
    /** 仅属于本模块的运行态待办集合。 */
    private static final String COLLECTION = "todos";

    /**
     * 返回与模块契约一致的稳定标识。
     *
     * <p>该值不得随运行状态、启用状态或设置变化。</p>
     */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 在集合尚不存在时写入首条示例待办。
     *
     * <p>前置条件是宿主已创建并交付受控运行时。初始化只写本模块自有集合；通过
     * {@link ModuleRuntime#hasItems(String)} 区分尚未初始化与已创建的空集合，因此重复调用
     * 不会覆盖现有数据或反复追加种子。</p>
     */
    @Override
    public void initialize(ModuleRuntime runtime) {
        if (runtime.hasItems(COLLECTION)) {
            return;
        }
        runtime.writeItems(COLLECTION, List.of(Map.of(
                "id", "todo-seed-1",
                "title", "完成季度复盘",
                "dueAt", "2026-11-20T18:00:00+08:00",
                "status", "OPEN",
                "createdAt", "2026-11-10T09:00:00+08:00"
        )));
    }

    /**
     * 执行契约声明的只读待办查询。
     *
     * <p>{@code todo.list} 返回当前集合的条目和数量。查询不写入集合、设置、审计或外部状态；
     * 查询 ID 与参数集合由宿主在调用前按契约校验。</p>
     */
    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        if (!"todo.list".equals(queryId)) {
            throw new IllegalArgumentException("待办模块未提供查询：" + queryId);
        }
        List<Map<String, Object>> items = runtime.readItems(COLLECTION);
        return Map.of("items", items, "count", items.size());
    }

    /**
     * 为待办写入动作复制执行前的完整自有集合。
     *
     * <p>该方法只读取状态，不产生副作用，返回的列表和条目均为新映射，可交给宿主序列化
     * 持久化。重复准备快照不会改变业务状态。当前唯一动作是 {@code todo.create}，因此快照
     * 足以通过整体覆盖恢复执行前集合。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of("items", copy(runtime.readItems(COLLECTION)));
    }

    /**
     * 创建一条本模块待办并原子替换自有集合。
     *
     * <p>只接受 {@code todo.create}。宿主必须在调用前完成模块启用、参数和当前风险策略下的
     * 授权与确认校验，并保证同一执行步骤至多进入这里一次；模块不自行去重。副作用是向
     * {@code todos} 追加新条目并生成框架唯一 ID。</p>
     */
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

    /**
     * 用执行前快照整体覆盖自有集合，撤销待办创建。
     *
     * <p>快照必须包含 {@code items} 数组。覆盖是幂等的：以同一快照重复调用不会追加更多条目。
     * 快照缺失或条目结构非法时拒绝回滚，由宿主记录失败和审计状态。</p>
     */
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

    /** 取得并裁剪必填字符串参数；空值由模块拒绝。 */
    private String required(ModuleInvocation invocation, String key) {
        Object value = invocation.arguments().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value.toString().trim();
    }

    /** 取得可选字符串参数并统一裁剪首尾空白。 */
    private String optional(ModuleInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments().get(key);
        return value == null ? fallback : value.toString().trim();
    }

    /** 复制顶层条目，避免快照复用运行时返回的可变映射。 */
    private List<Map<String, Object>> copy(List<Map<String, Object>> items) {
        return items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList();
    }

    /**
     * 将宿主恢复的 JSON 快照严格转换为待办条目列表。
     *
     * <p>只接受列表和对象条目；任何结构错位都抛出异常，避免不完整快照静默覆盖业务集合。</p>
     */
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
