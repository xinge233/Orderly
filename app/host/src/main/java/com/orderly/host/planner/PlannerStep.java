package com.orderly.host.planner;

import java.util.Map;

/**
 * 本地规则计划器识别出的单条待校验意图。
 *
 * <p>该对象只携带 Action ID 和规则提取参数，不包含授权、风险或执行状态。宿主必须
 * 再次从契约索引查找 Action、验证模块启用与参数后，才能把它转换为执行步骤。</p>
 *
 * @param actionId 契约中的动作 ID
 * @param arguments 本地规则提取的顶层参数
 */
public record PlannerStep(
        String actionId,
        Map<String, Object> arguments
) {
}
