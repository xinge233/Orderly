package com.orderly.host.execution;

import java.util.Map;

/** 模块动作执行失败；输出校验失败时会携带已发生的正向结果供回滚。 */
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

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    public Map<String, Object> output() {
        return output;
    }

    public boolean executed() {
        return executed;
    }
}
