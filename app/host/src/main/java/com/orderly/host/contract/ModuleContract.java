package com.orderly.host.contract;

import java.util.List;

/**
 * 单个模块的完整公开契约。
 *
 * @param moduleId 模块 ID
 * @param title 标题
 * @param description 说明
 * @param order 导航顺序
 * @param system 可选的系统模块元数据
 * @param actions 动作
 * @param queries 只读查询
 * @param plannerRules 本地计划规则
 */
public record ModuleContract(
        String moduleId,
        String title,
        String description,
        int order,
        ModuleSystemMetadata system,
        List<ActionContract> actions,
        List<QueryContract> queries,
        List<PlannerRuleContract> plannerRules
) {
    public ModuleContract {
        actions = List.copyOf(actions);
        queries = List.copyOf(queries);
        plannerRules = List.copyOf(plannerRules);
    }
}
