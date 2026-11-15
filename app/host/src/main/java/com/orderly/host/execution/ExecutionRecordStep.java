package com.orderly.host.execution;

import com.orderly.kernel.RiskLevel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 持久化执行记录中的单个动作步骤。 */
public record ExecutionRecordStep(
        String stepId,
        String moduleId,
        String actionId,
        String title,
        RiskLevel risk,
        Map<String, Object> arguments,
        String status,
        Map<String, Object> snapshot,
        Map<String, Object> output,
        String message
) {
    public ExecutionRecordStep {
        arguments = copy(arguments);
        snapshot = copy(snapshot);
        output = copy(output);
        status = status == null ? "WAITING" : status;
        message = message == null ? "" : message;
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                value == null ? Map.of() : value
        ));
    }
}
