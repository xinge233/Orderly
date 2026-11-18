package com.orderly.kernel;

/** 框架统一识别的 Action 破坏性等级，用于授权、确认和执行策略。 */
public enum RiskLevel {
    /** 只读动作，不得改变文件、集合、设置或外部状态。 */
    READ_ONLY,
    /** 可逆写入动作，必须由宿主执行风险策略并在变更前保留快照。 */
    WRITE,
    /** 删除语义动作；只能移入项目回收站，永不执行物理删除。 */
    DELETE
}
