package com.orderly.host.plan;

import com.orderly.kernel.RiskLevel;

import java.util.List;
import java.util.Map;

/**
 * 可安全返回前端的计划步骤视图。
 *
 * <p>视图省略内部快照和结构化输出，只暴露审查、执行状态和审计摘要所需字段。各集合
 * 与参数映射由上游对象提供，本记录不额外执行深拷贝。</p>
 *
 * @param id 步骤 ID
 * @param moduleId 动作所属模块
 * @param actionId 动作 ID
 * @param title 动作标题
 * @param description 动作说明
 * @param arguments 宿主已校验的顶层参数
 * @param risk 契约风险等级
 * @param dependsOn 前序步骤 ID
 * @param status 当前步骤状态名
 * @param message 最近一次执行或回滚摘要
 */
public record PlanStepView(
        String id,
        String moduleId,
        String actionId,
        String title,
        String description,
        Map<String, Object> arguments,
        RiskLevel risk,
        List<String> dependsOn,
        String status,
        String message
) {
}
