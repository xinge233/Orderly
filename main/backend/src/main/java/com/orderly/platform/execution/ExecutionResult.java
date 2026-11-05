package com.orderly.platform.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 执行管线的统一结果。
 *
 * @param outcome 最终结果类型
 * @param requestId 原始请求标识
 * @param message 可读说明
 * @param completedStages 已进入的阶段
 * @param failedAt 被拒绝或失败时所在的阶段；成功时为 null
 * @param output 模块返回的结构化只读输出
 */
public record ExecutionResult(
        Outcome outcome,
        String requestId,
        String message,
        List<ExecutionStage> completedStages,
        ExecutionStage failedAt,
        Map<String, Object> output
) {
    public ExecutionResult {
        outcome = Objects.requireNonNull(outcome, "执行结果类型不能为空");
        requestId = Objects.requireNonNullElse(requestId, "");
        message = Objects.requireNonNullElse(message, "");
        completedStages = List.copyOf(Objects.requireNonNullElse(completedStages, List.of()));
        output = Map.copyOf(Objects.requireNonNullElse(output, Map.of()));
    }

    /**
     * 管线最终结果分类，不复用任务状态机。
     */
    public enum Outcome {
        COMPLETED,
        REJECTED,
        FAILED
    }

    /**
     * 创建成功结果。
     *
     * @param requestId 请求标识
     * @param message 说明
     * @param stages 已进入阶段
     * @param output 模块输出
     * @return 成功结果
     */
    public static ExecutionResult completed(
            String requestId,
            String message,
            List<ExecutionStage> stages,
            Map<String, Object> output
    ) {
        return new ExecutionResult(
                Outcome.COMPLETED,
                requestId,
                message,
                stages,
                null,
                output
        );
    }

    /**
     * 创建执行前被拒绝的结果。
     *
     * @param requestId 请求标识
     * @param stage 拒绝阶段
     * @param message 拒绝原因
     * @param stages 已进入阶段
     * @return 拒绝结果
     */
    public static ExecutionResult rejected(
            String requestId,
            ExecutionStage stage,
            String message,
            List<ExecutionStage> stages
    ) {
        return new ExecutionResult(
                Outcome.REJECTED,
                requestId,
                message,
                stages,
                stage,
                Map.of()
        );
    }

    /**
     * 创建执行失败结果。
     *
     * @param requestId 请求标识
     * @param stage 失败阶段
     * @param message 失败原因
     * @param stages 已进入阶段
     * @return 失败结果
     */
    public static ExecutionResult failed(
            String requestId,
            ExecutionStage stage,
            String message,
            List<ExecutionStage> stages
    ) {
        return new ExecutionResult(
                Outcome.FAILED,
                requestId,
                message,
                stages,
                stage,
                Map.of()
        );
    }
}
