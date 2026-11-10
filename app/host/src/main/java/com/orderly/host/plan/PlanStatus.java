package com.orderly.host.plan;

/** 计划生命周期状态。 */
public enum PlanStatus {
    PREVIEW,
    RUNNING,
    COMPLETED,
    REJECTED,
    PARTIALLY_ROLLED_BACK,
    ROLLED_BACK
}
