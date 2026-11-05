package com.orderly.platform.module;

import com.orderly.platform.execution.ExecutionPipeline;
import com.orderly.platform.execution.ExecutionRequest;
import com.orderly.platform.execution.ExecutionResult;
import org.springframework.stereotype.Service;

/**
 * 模块调度的兼容入口。
 *
 * <p>保留此类是为了避免现有调用方直接依赖执行管线实现。它本身不查找模块、
 * 不执行模块，只把所有请求委托给 {@link ExecutionPipeline}，因此不构成
 * 绕过校验、确认和授权的旁路。</p>
 */
@Service
public class ModuleDispatchService {

    private final ExecutionPipeline executionPipeline;

    /**
     * 注入中央执行管线。
     *
     * @param executionPipeline 唯一受控执行入口
     */
    public ModuleDispatchService(ExecutionPipeline executionPipeline) {
        this.executionPipeline = executionPipeline;
    }

    /**
     * 将执行请求委托给中央安全管线。
     *
     * @param request 执行请求
     * @return 带阶段追踪的执行结果
     */
    public ExecutionResult dispatch(ExecutionRequest request) {
        return executionPipeline.execute(request);
    }
}
