package com.orderly.host.plan;

import java.time.Instant;
import java.util.List;

/**
 * 旧版计划及当前执行状态的前端视图。
 *
 * <p>实例是生成时的只读展示快照，不包含步骤快照或模块输出，也不能替代持久化执行
 * 记录。计划对象仍在内存中变化时，需重新调用 {@link #from(ExecutionPlan)} 获取新视图。</p>
 *
 * @param planId 计划 ID
 * @param goal 用户目标
 * @param kind 计划器结果类型
 * @param message 计划或澄清说明
 * @param assumptions 本地规则计划器的假设
 * @param impact 预计影响摘要
 * @param confidence 本地规则置信度
 * @param status 聚合状态名
 * @param createdAt 创建时间
 * @param completedAt 进入终止态的时间；未终止时为空
 * @param steps 步骤展示视图
 */
public record PlanView(
        String planId,
        String goal,
        String kind,
        String message,
        List<String> assumptions,
        String impact,
        double confidence,
        String status,
        Instant createdAt,
        Instant completedAt,
        List<PlanStepView> steps
) {
    /**
     * 把可变计划对象投影为前端可读取的展示快照。
     *
     * <p>转换只读取当前字段并映射步骤状态，不修改计划、不执行模块，也不会持久化。
     * 列表中只保留展示字段，因此该视图不能用于回滚或恢复。</p>
     */
    public static PlanView from(ExecutionPlan plan) {
        return new PlanView(
                plan.id(),
                plan.goal(),
                plan.kind(),
                plan.message(),
                plan.assumptions(),
                plan.impact(),
                plan.confidence(),
                plan.status().name(),
                plan.createdAt(),
                plan.completedAt(),
                plan.steps().stream().map(step -> new PlanStepView(
                        step.id(),
                        step.moduleId(),
                        step.actionId(),
                        step.title(),
                        step.description(),
                        step.arguments(),
                        step.risk(),
                        step.dependsOn(),
                        step.status().name(),
                        step.message()
                )).toList()
        );
    }
}
