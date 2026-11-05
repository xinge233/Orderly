package com.orderly.platform.execution;

/**
 * 中央执行管线的固定阶段。
 *
 * <p>枚举顺序就是安全顺序。任何模块动作都必须先经过校验、确认和工作区授权，
 * 才可能进入执行阶段；快照与审计随后发生，失败时再进入回滚阶段。</p>
 */
public enum ExecutionStage {
    VALIDATE_ACTION,
    CONFIRM_PLAN,
    AUTHORIZE_WORKSPACE,
    PREPARE_SNAPSHOT,
    EXECUTE,
    SNAPSHOT_AUDIT,
    ROLLBACK_ON_FAILURE
}
