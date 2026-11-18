package com.orderly.host.modulecontrol;

import com.orderly.host.contract.ModuleEntry;

import java.util.List;

/**
 * 一次显式模块刷新的结果。
 *
 * <p>added 只包含本次成功追加并提交的业务模块，issues 记录未构建、加载失败或需要
 * 重启的变化，revision 对应刷新结束后的治理状态代次。</p>
 */
public record ModuleRefreshResult(
        List<ModuleEntry> added,
        List<ModuleRefreshIssue> issues,
        String revision
) {
    /** 复制结果列表，避免调用方修改刷新结果。 */
    public ModuleRefreshResult {
        added = List.copyOf(added);
        issues = List.copyOf(issues);
    }
}
