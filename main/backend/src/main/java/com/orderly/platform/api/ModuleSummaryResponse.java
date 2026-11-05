package com.orderly.platform.api;

import java.util.List;

/**
 * 模块目录中的公开模块摘要。
 *
 * <p>该 DTO 与内部 {@code ModuleDescriptor} 解耦，内部字段调整不会自动泄露到 API。</p>
 *
 * @param id 模块标识
 * @param displayName 模块名称
 * @param description 模块说明
 * @param order 展示顺序
 * @param actions 已声明动作
 */
public record ModuleSummaryResponse(
        String id,
        String displayName,
        String description,
        int order,
        List<ModuleActionResponse> actions
) {
    public ModuleSummaryResponse {
        actions = List.copyOf(actions);
    }
}
