package com.orderly.host.plan;

import com.orderly.kernel.RiskLevel;

import java.util.List;
import java.util.Map;

/** 可安全返回前端的步骤视图。 */
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
