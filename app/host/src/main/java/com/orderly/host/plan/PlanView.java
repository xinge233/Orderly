package com.orderly.host.plan;

import java.time.Instant;
import java.util.List;

/** 计划及执行状态的前端视图。 */
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
