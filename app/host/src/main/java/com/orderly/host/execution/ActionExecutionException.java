package com.orderly.host.execution;

import java.util.Map;

/**
 * 模块动作执行失败，并携带回滚所需的执行事实。
 *
 * <p>{@code executed} 表示正向副作用是否可能已经发生。输出 schema 校验失败时
 * 正向调用通常已成功，因此上层仍应把该步骤纳入逆序回滚；快照失败或模块调用
 * 自身失败时则由捕获点决定是否已执行。快照与输出均做不可变顶层复制。</p>
 */
public class ActionExecutionException extends RuntimeException {

    private final Map<String, Object> snapshot;
    private final Map<String, Object> output;
    private final boolean executed;

    public ActionExecutionException(
            String message,
            Throwable cause,
            Map<String, Object> snapshot,
            Map<String, Object> output,
            boolean executed
    ) {
        super(message, cause);
        this.snapshot = Map.copyOf(snapshot == null ? Map.of() : snapshot);
        this.output = Map.copyOf(output == null ? Map.of() : output);
        this.executed = executed;
    }

    /** 返回可用于逆操作的顶层快照映射，可能为空。 */
    public Map<String, Object> snapshot() {
        return snapshot;
    }

    /** 返回正向调用产生的顶层输出映射，可能为空。 */
    public Map<String, Object> output() {
        return output;
    }

    /** 指示在抛出异常前是否已发生或可能已发生正向副作用。 */
    public boolean executed() {
        return executed;
    }
}
