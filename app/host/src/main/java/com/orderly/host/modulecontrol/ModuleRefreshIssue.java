package com.orderly.host.modulecontrol;

/**
 * 模块刷新中的单项问题。
 *
 * <p>code 是稳定的机器可读原因，message 是返回给用户的定位说明；典型 code 包括
 * MODULE_NOT_BUILT、MODULE_LOAD_FAILED 和 RESTART_REQUIRED。</p>
 */
public record ModuleRefreshIssue(String code, String message) {
}
