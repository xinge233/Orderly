package com.orderly.host.execution;

import com.orderly.host.plan.PlanView;

/** 计划确认、自动回滚或用户整单回滚后的响应，携带状态、结果说明和计划视图。 */
public record ExecutionRunResult(
        String status,
        String outcome,
        String message,
        PlanView plan
) {
}
