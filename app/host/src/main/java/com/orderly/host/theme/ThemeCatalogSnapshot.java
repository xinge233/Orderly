package com.orderly.host.theme;

import java.util.List;

/**
 * 启动时生成的主题目录稳定快照。
 *
 * <p>列表在构造时复制。{@code builtInFallbackActive} 表示当前可用主题中没有
 * {@code default: true}，前端应使用内置安全外观，并不等同于主题目录完全读取失败。</p>
 *
 * @param items 可用主题摘要
 * @param issues 被隔离的单主题问题
 * @param builtInFallbackActive 是否需要启用内置安全外观
 */
public record ThemeCatalogSnapshot(
        List<ThemeSummary> items,
        List<ThemeIssue> issues,
        boolean builtInFallbackActive
) {
    /** 冻结主题摘要和问题列表的顶层顺序；已有元素对象保持原样。 */
    public ThemeCatalogSnapshot {
        items = List.copyOf(items);
        issues = List.copyOf(issues);
    }
}
