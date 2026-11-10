package com.orderly.host.planner;

import java.util.List;

/** 计划或澄清问题的结构化输出。 */
public record PlannerResult(
        String kind,
        String message,
        List<String> assumptions,
        String impact,
        double confidence,
        List<PlannerStep> steps
) {
}
