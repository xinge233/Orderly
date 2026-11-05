package com.orderly.platform.execution;

import java.util.Map;
import java.util.Objects;

/**
 * 中央执行管线的结构化请求。
 *
 * <p>请求只承载计划标识、目标模块、动作和参数，不包含可直接执行的命令字符串。
 * 是否允许执行由管线结合动作定义、服务端确认和工作区授权决定。</p>
 *
 * @param requestId 可追踪的请求标识
 * @param planId 所属计划标识
 * @param moduleId 目标模块标识
 * @param actionId 目标动作标识
 * @param parameters 只读参数集合
 * @param confirmationToken 服务端确认凭据；无确认要求的动作可为空
 */
public record ExecutionRequest(
        String requestId,
        String planId,
        String moduleId,
        String actionId,
        Map<String, Object> parameters,
        String confirmationToken
) {
    public ExecutionRequest {
        requestId = requireText(requestId, "请求 id");
        planId = requireText(planId, "计划 id");
        moduleId = requireText(moduleId, "模块 id");
        actionId = requireText(actionId, "动作 id");
        parameters = Map.copyOf(Objects.requireNonNullElse(parameters, Map.of()));
        confirmationToken = Objects.requireNonNullElse(confirmationToken, "");
    }

    /**
     * 校验必填文本字段。
     *
     * @param value 原始值
     * @param label 字段名称
     * @return 去除首尾空白后的值
     */
    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return normalized;
    }
}
