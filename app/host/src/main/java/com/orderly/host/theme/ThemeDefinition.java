package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** 完整主题定义，仅供本地前端通过宿主 API 读取。 */
public record ThemeDefinition(
        String id,
        String label,
        String note,
        int order,
        @JsonProperty("default") boolean defaultTheme,
        String colorScheme,
        String browserColor,
        Map<String, String> tokens
) {
    public ThemeDefinition {
        tokens = Map.copyOf(tokens);
    }

    public ThemeSummary summary() {
        return new ThemeSummary(id, label, note, order, defaultTheme);
    }
}