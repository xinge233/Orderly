package com.orderly.host.modulecontrol;

import com.orderly.host.contract.ModuleEntry;

import java.util.List;

public record ModuleRefreshResult(
        List<ModuleEntry> added,
        List<ModuleRefreshIssue> issues,
        String revision
) {
    public ModuleRefreshResult {
        added = List.copyOf(added);
        issues = List.copyOf(issues);
    }
}
