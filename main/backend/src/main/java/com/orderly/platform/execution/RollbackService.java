package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;

/**
 * 失败处理与回滚的统一扩展点。
 */
@FunctionalInterface
public interface RollbackService {

    /**
     * 根据执行前快照处理失败。
     *
     * @param request 执行请求
     * @param action 动作定义
     * @param snapshot 执行前快照
     * @param reason 失败原因
     * @return 回滚结果
     */
    RollbackResult rollback(
            ExecutionRequest request,
            ModuleActionDefinition action,
            ExecutionSnapshot snapshot,
            String reason
    );
}
