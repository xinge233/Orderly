package com.orderly.platform.execution;

/**
 * 中央审计事件。
 *
 * <p>审计由执行管线统一生成；模块即使返回失败也不能跳过该事件。
 * 当前实现只保存在内存或静默记录，尚未连接持久化数据库。</p>
 *
 * @param requestId 请求标识
 * @param planId 计划标识
 * @param moduleId 模块标识
 * @param actionId 动作标识
 * @param stage 事件所在阶段
 * @param outcome 结果分类
 * @param message 可读说明
 * @param snapshot 对应快照
 */
public record ExecutionAuditEvent(
        String requestId,
        String planId,
        String moduleId,
        String actionId,
        ExecutionStage stage,
        ExecutionResult.Outcome outcome,
        String message,
        ExecutionSnapshot snapshot
) {
}
