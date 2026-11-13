package com.orderly.host.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 公开模块目录条目，附带当前运行态的启用状态。 */
public record ModuleEntry(
        String moduleId,
        String title,
        String description,
        int order,
        List<ActionContract> actions,
        List<QueryContract> queries,
        List<PlannerRuleContract> plannerRules,
        @JsonInclude(JsonInclude.Include.NON_NULL) ModuleSystemMetadata system,
        boolean enabled
) {
    public ModuleEntry {
        actions = List.copyOf(actions);
        queries = List.copyOf(queries);
        plannerRules = List.copyOf(plannerRules);
    }

    public static ModuleEntry from(ModuleContract module, boolean enabled) {
        return new ModuleEntry(
                module.moduleId(),
                module.title(),
                module.description(),
                module.order(),
                module.actions(),
                module.queries(),
                module.plannerRules(),
                module.system(),
                enabled
        );
    }
}
