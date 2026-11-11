package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 主题列表使用的稳定摘要。 */
public record ThemeSummary(
        String id,
        String label,
        String note,
        int order,
        @JsonProperty("default") boolean defaultTheme
) {
}