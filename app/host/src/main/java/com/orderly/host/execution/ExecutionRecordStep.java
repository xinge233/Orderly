package com.orderly.host.execution;

import com.orderly.kernel.RiskLevel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化执行记录中的单个动作步骤。
 *
 * <p>步骤保存风险、已解析参数、状态、快照、输出和审计摘要。构造时统一空值并做
 * 顶层不可变复制；状态字符串由执行协调器控制，不由该 DTO 推导状态迁移。</p>
 */
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

    /** 复制参与持久化或回滚的顶层映射，避免调用方后续修改影响记录快照。 */
    private static Map<String, Object> copy(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                value == null ? Map.of() : value
        ));
    }
}
