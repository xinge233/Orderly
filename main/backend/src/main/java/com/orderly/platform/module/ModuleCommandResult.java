package com.orderly.platform.module;

import java.util.Map;
import java.util.Objects;

/**
 * 模块命令的统一结果结构。
 *
 * @param status 调度结果状态
 * @param message 面向调用方的简短说明
 * @param output 模块返回的结构化只读输出
 */
public record ModuleCommandResult(
        Status status,
        String message,
        Map<String, Object> output
) {
    public ModuleCommandResult {
        status = Objects.requireNonNull(status, "结果状态不能为空");
        message = Objects.requireNonNullElse(message, "");
        output = Map.copyOf(Objects.requireNonNullElse(output, Map.of()));
    }

    /**
     * 命令生命周期中最小的一组结果状态。
     *
     * <p>这些状态描述“单次模块命令”的调度结果，不复用任务状态机的状态，
     * 避免前端把模块响应误认为整个任务的完成状态。</p>
     */
    public enum Status {
        COMPLETED,
        REJECTED
    }

    /**
     * 创建成功完成的空操作结果。
     *
     * @param message 成功说明
     * @return 不可变结果
     */
    public static ModuleCommandResult completed(String message) {
        return new ModuleCommandResult(Status.COMPLETED, message, Map.of());
    }

    /**
     * 创建被拒绝的结果。
     *
     * @param message 拒绝原因
     * @return 不可变结果
     */
    public static ModuleCommandResult rejected(String message) {
        return new ModuleCommandResult(Status.REJECTED, message, Map.of());
    }
}
