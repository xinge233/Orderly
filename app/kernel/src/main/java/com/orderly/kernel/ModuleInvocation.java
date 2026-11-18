package com.orderly.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 框架签发给模块的单个 Action 调用上下文。
 *
 * <p>该对象记录宿主已选定的模块、动作、计划和步骤身份，以及校验后的顶层参数。
 * 它证明调用来自当前执行链，但不替代宿主对风险、确认和模块启用状态的检查；
 * 模块不得据此自行扩展授权或调度其他模块。</p>
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
    /**
     * 规范化调用身份并复制顶层参数映射。
     *
     * <p>身份字段不能为 {@code null}；参数映射为 {@code null} 时视为空映射。
     * 复制只保证映射本身不可变，嵌套 JSON 值仍按其中的对象类型处理。构造过程
     * 不访问文件系统，也不改变宿主的执行状态。</p>
     */
    public ModuleInvocation {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        arguments = Map.copyOf(Objects.requireNonNullElse(arguments, Map.of()));
    }
}
