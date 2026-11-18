package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 主题列表和设置界面使用的稳定摘要。
 *
 * <p>摘要有意省略资源列表与磁盘根路径，只暴露排序、显示和默认选择所需元数据。实例
 * 是不可变记录，不触发主题重载。</p>
 *
 * @param schemaVersion 清单版本
 * @param id 稳定主题 ID
 * @param label 显示名称
 * @param note 可选说明
 * @param order 排序值
 * @param defaultTheme 是否为默认主题
 */
public record ThemeSummary(
        int schemaVersion,
        String id,
        String label,
        String note,
        int order,
        @JsonProperty("default") boolean defaultTheme
) {
}
