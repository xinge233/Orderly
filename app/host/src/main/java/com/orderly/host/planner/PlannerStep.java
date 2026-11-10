package com.orderly.host.planner;

import java.util.Map;

/** 本地规则计划器输出的单条意图。 */
public record PlannerStep(
        String actionId,
        Map<String, Object> arguments
) {
}
