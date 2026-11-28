package com.orderly.host.execution;

import java.time.Instant;

/** 执行记录列表使用的轻量摘要，不包含步骤快照、输出或完整事件列表。 */
public record ExecutionRecordSummary(
        String executionId,
        String goal,
        String origin,
        String status,
        int stepCount,
        boolean canRollback,
        String rollbackDisabledReason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
}
