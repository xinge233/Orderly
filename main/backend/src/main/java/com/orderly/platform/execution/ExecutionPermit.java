package com.orderly.platform.execution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 由中央执行管线签发的一次性执行许可。
 *
 * <p>类构造器只在 execution 包内可见，模块不能自行伪造许可。许可绑定请求、
 * 模块和动作，并且只能被消费一次，用于阻止直接调用或重复执行。</p>
 */
public final class ExecutionPermit {

    private final String requestId;
    private final String moduleId;
    private final String actionId;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    /**
     * 仅允许中央管线签发许可。
     *
     * @param requestId 请求标识
     * @param moduleId 模块标识
     * @param actionId 动作标识
     */
    ExecutionPermit(String requestId, String moduleId, String actionId) {
        this.requestId = Objects.requireNonNull(requestId);
        this.moduleId = Objects.requireNonNull(moduleId);
        this.actionId = Objects.requireNonNull(actionId);
    }

    /**
     * 校验许可范围并一次性消费。
     *
     * @param expectedModuleId 当前模块
     * @param expectedActionId 当前动作
     */
    public void requireFor(String expectedModuleId, String expectedActionId) {
        if (!moduleId.equals(expectedModuleId) || !actionId.equals(expectedActionId)) {
            throw new SecurityException("执行许可与当前模块或动作不匹配");
        }
        if (!consumed.compareAndSet(false, true)) {
            throw new SecurityException("执行许可已被使用");
        }
    }

    /**
     * 返回许可绑定的请求标识，便于模块写入不含敏感信息的审计上下文。
     *
     * @return 请求标识
     */
    public String requestId() {
        return requestId;
    }
}
