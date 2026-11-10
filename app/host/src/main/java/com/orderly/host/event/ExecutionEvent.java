package com.orderly.host.event;

import java.time.Instant;

/**
 * 只追加执行事件。
 *
 * @param seq 单调递增序号
 * @param type 事件类型
 * @param planId 计划 ID
 * @param stepId 步骤 ID，可为空
 * @param moduleId 模块 ID，可为空
 * @param actionId 动作 ID，可为空
 * @param status 状态
 * @param message 摘要
 * @param timestamp 时间
 */
public record ExecutionEvent(
        long seq,
        String type,
        String planId,
        String stepId,
        String moduleId,
        String actionId,
        String status,
        String message,
        Instant timestamp
) {
}
