package com.orderly.platform.module;

/**
 * 动作风险等级。
 *
 * <p>中央安全策略依据该等级决定确认要求。写入与破坏性动作在定义阶段
 * 必须显式声明需要确认，否则模块注册会直接失败。</p>
 */
public enum ActionRiskLevel {
    READ_ONLY,
    WRITE,
    DESTRUCTIVE
}
