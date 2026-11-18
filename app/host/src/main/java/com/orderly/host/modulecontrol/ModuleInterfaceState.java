package com.orderly.host.modulecontrol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组设置界面使用的接口级运行态快照。
 *
 * <p>包含 Action/Query 动态 AI 开关映射和使用中的 Action 键。构造时复制嵌套映射与
 * 列表，调用方修改源集合不会改变该快照。</p>
 */
public record ModuleInterfaceState(
        Map<String, Map<String, Boolean>> actionAiCallableEnabled,
        Map<String, Map<String, Boolean>> queryAiCallableEnabled,
        List<String> inUseActions
) {

    public ModuleInterfaceState {
        actionAiCallableEnabled = copy(actionAiCallableEnabled);
        queryAiCallableEnabled = copy(queryAiCallableEnabled);
        inUseActions = List.copyOf(inUseActions);
    }

    /** 返回无开关、无使用中 Action 的空状态。 */
    public static ModuleInterfaceState empty() {
        return new ModuleInterfaceState(Map.of(), Map.of(), List.of());
    }

    /** 将两级开关映射复制为不可变结构。 */
    private static Map<String, Map<String, Boolean>> copy(
            Map<String, Map<String, Boolean>> source
    ) {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        source.forEach((moduleId, values) -> result.put(moduleId, Map.copyOf(values)));
        return Map.copyOf(result);
    }
}
