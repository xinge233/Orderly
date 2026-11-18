package com.orderly.host.plan;

/** 旧版计划从预览、执行到完成或回滚的聚合生命周期状态。 */
public enum PlanStatus {
    /** 已生成但尚未确认；只有此状态允许首次进入执行。 */
    PREVIEW,
    /** 已取得执行许可并创建运行记录，正在顺序执行步骤。 */
    RUNNING,
    /** 全部步骤成功完成，仍可按已完成步骤逆序回滚。 */
    COMPLETED,
    /** 执行被拒绝或发生失败；步骤可能已全部自动回滚，也可能从未执行。 */
    REJECTED,
    /** 自动或用户回滚未完全成功，仍有可检查或继续恢复的步骤。 */
    PARTIALLY_ROLLED_BACK,
    /** 原已完成步骤均已成功逆序回滚。 */
    ROLLED_BACK
}
