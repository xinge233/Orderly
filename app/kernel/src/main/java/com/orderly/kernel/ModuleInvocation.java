package com.orderly.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 框架签发给模块的一次动作上下文。
 *
 * <p>模块只应把该对象视为已通过宿主校验的调用凭据，不能自行扩展授权。</p>
 *
 * @param moduleId 模块 ID
 * @param actionId 动作 ID
 * @param planId 计划 ID
 * @param stepId 步骤 ID
 * @param arguments 已校验参数
 */
public record ModuleInvocation(
        String moduleId,
        String actionId,
        String planId,
        String stepId,
        Map<String, Object> arguments
) {
    public ModuleInvocation {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        arguments = Map.copyOf(Objects.requireNonNullElse(arguments, Map.of()));
    }
}
