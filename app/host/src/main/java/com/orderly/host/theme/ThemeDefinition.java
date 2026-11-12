package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.List;

/** 完整主题定义，仅供本地前端通过宿主 API 读取。 */
public record ThemeDefinition(
        int schemaVersion,
        String id,
        String label,
        String note,
        int order,
        @JsonProperty("default") boolean defaultTheme,
        String colorScheme,
        String browserColor,
        List<String> styles,
        List<String> assets,
        @JsonIgnore Path rootPath
) {
    public ThemeDefinition {
        styles = List.copyOf(styles);
        assets = List.copyOf(assets);
        rootPath = rootPath.toAbsolutePath().normalize();
    }

    public ThemeSummary summary() {
        return new ThemeSummary(schemaVersion, id, label, note, order, defaultTheme);
    }
}
