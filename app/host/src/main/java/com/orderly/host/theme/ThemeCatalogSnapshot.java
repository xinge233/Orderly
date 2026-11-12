package com.orderly.host.theme;

import java.util.List;

/** 主题目录的稳定启动快照。 */
public record ThemeCatalogSnapshot(
        List<ThemeSummary> items,
        List<ThemeIssue> issues,
        boolean builtInFallbackActive
) {
    public ThemeCatalogSnapshot {
        items = List.copyOf(items);
        issues = List.copyOf(issues);
    }
}
