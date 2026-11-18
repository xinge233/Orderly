package com.orderly.host.planner;

import java.util.List;

/**
 * 本地规则计划器产生的一次结构化输出。
 *
 * <p>{@code kind} 为 {@code PLAN} 时步骤可进入上层契约校验；为 {@code QUESTION} 时
 * 步骤为空，只表达澄清或能力缺口。该记录不证明已连接模型，也不证明计划可执行。</p>
 *
 * @param kind 结果类型
 * @param message 面向用户的计划或澄清说明
 * @param assumptions 本地规则与安全边界假设
 * @param impact 预计影响；澄清结果应明确未变更
 * @param confidence 本地匹配置信度，不代表模型置信度
 * @param steps 待宿主二次校验的规则步骤
 */
public record PlannerResult(
        String kind,
        String message,
        List<String> assumptions,
        String impact,
        double confidence,
        List<PlannerStep> steps
) {
}
