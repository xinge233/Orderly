package com.orderly.platform.module;

import java.util.Map;
import java.util.Objects;

/**
 * 跨模块调度的统一命令信封。
 *
 * <p>命令只负责携带目标、动作和参数，不代表动作已经获准执行。
 * 后续接入真实工具时，必须在调度层之上增加计划确认、权限和工作区校验。</p>
 *
 * @param moduleId 目标模块标识
 * @param action 目标模块声明的动作名称
 * @param parameters 只读参数集合
 */
public record ModuleCommand(
        String moduleId,
        String action,
        Map<String, Object> parameters
) {
    public ModuleCommand {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("目标模块 id 不能为空");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("动作名称不能为空");
        }
        parameters = Map.copyOf(Objects.requireNonNullElse(parameters, Map.of()));
    }
}
