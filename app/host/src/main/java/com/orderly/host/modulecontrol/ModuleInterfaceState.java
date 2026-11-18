package com.orderly.host.modulecontrol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模组设置界面使用的接口级运行态快照。 */
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

    public static ModuleInterfaceState empty() {
        return new ModuleInterfaceState(Map.of(), Map.of(), List.of());
    }

    private static Map<String, Map<String, Boolean>> copy(
            Map<String, Map<String, Boolean>> source
    ) {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        source.forEach((moduleId, values) -> result.put(moduleId, Map.copyOf(values)));
        return Map.copyOf(result);
    }
}
