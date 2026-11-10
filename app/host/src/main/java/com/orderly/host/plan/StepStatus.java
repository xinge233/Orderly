package com.orderly.host.plan;

/** 单步状态。 */
public enum StepStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}
