package com.orderly.host.execution;

import java.util.Set;

/**
 * 由宿主统一判定执行记录能否再次回滚。
 *
 * <p>状态白名单与执行服务共用，避免历史摘要和实际回滚对同一记录给出相反结论。
 * 只有仍存在 COMPLETED 步骤或上次回滚失败、可重试的 ROLLBACK_FAILED 步骤时，
 * 记录才允许回滚。</p>
 */
public final class ExecutionRollbackPolicy {

    private static final Set<String> ROLLBACKABLE_STATUSES = Set.of(
            "COMPLETED",
            "PARTIALLY_ROLLED_BACK",
            "FAILED_PARTIALLY_ROLLED_BACK"
    );

    private ExecutionRollbackPolicy() {
    }

    /** 返回宿主权威的可回滚性及不可回滚原因。 */
    public static Assessment assess(ExecutionRecord record) {
        if (record == null) {
            return new Assessment(false, "执行记录不存在。");
        }
        if (!ROLLBACKABLE_STATUSES.contains(record.status())) {
            return new Assessment(
                    false,
                    "执行记录状态为 " + record.status() + "，当前不可回滚。"
            );
        }
        boolean hasRollbackCandidate = record.steps().stream()
                .anyMatch(ExecutionRollbackPolicy::isRollbackCandidate);
        if (!hasRollbackCandidate) {
            return new Assessment(false, "执行记录没有可回滚或可重试的步骤。");
        }
        return new Assessment(true, "");
    }

    /** 首次回滚候选与上次失败后的重试候选使用同一状态集合。 */
    public static boolean isRollbackCandidate(ExecutionRecordStep step) {
        return step != null && (
                "COMPLETED".equals(step.status())
                        || "ROLLBACK_FAILED".equals(step.status())
        );
    }

    /** 回滚资格及供前端展示的原因。 */
    public record Assessment(boolean canRollback, String reason) {
    }
}
