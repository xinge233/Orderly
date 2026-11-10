package com.orderly.host.execution;

import com.orderly.host.plan.PlanView;

/** 计划执行或回滚后的响应。 */
public record ExecutionRunResult(
        String status,
        String outcome,
        String message,
        PlanView plan
) {
}
