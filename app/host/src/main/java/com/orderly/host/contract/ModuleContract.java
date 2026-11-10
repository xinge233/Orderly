package com.orderly.host.contract;

import java.util.List;

/**
 * 单个模块的完整公开契约。
 *
 * @param moduleId 模块 ID
 * @param title 标题
 * @param description 说明
 * @param order 导航顺序
 * @param actions 动作
 */
public record ModuleContract(
        String moduleId,
        String title,
        String description,
        int order,
        List<ActionContract> actions
) {
    public ModuleContract {
        actions = List.copyOf(actions);
    }
}
