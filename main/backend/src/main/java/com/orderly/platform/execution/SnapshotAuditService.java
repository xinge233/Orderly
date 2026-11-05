package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;

/**
 * 快照与审计的集中扩展点。
 */
public interface SnapshotAuditService {

    /**
     * 在执行前准备快照。
     *
     * @param request 执行请求
     * @param action 动作定义
     * @return 不可变快照
     */
    ExecutionSnapshot prepare(ExecutionRequest request, ModuleActionDefinition action);

    /**
     * 记录执行结果或拒绝原因。
     *
     * @param event 审计事件
     */
    void record(ExecutionAuditEvent event);
}
