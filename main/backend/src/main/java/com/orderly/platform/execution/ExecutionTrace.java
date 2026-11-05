package com.orderly.platform.execution;

/**
 * 中央管线阶段观察器。
 *
 * <p>实现只能观察阶段，不得改变执行决策。测试使用它验证固定顺序，
 * 未来的进度服务也可以在这里把阶段映射为 SSE 事件。</p>
 */
@FunctionalInterface
public interface ExecutionTrace {

    /**
     * 记录进入某个阶段。
     *
     * @param stage 当前阶段
     * @param request 执行请求
     */
    void onStage(ExecutionStage stage, ExecutionRequest request);
}
