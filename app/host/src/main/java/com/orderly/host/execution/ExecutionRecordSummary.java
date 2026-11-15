package com.orderly.host.execution;

import java.time.Instant;

/** 执行记录列表摘要。 */
public record ExecutionRecordSummary(
        String executionId,
        String origin,
        String status,
        int stepCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
}
