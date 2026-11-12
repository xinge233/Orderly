package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 单个主题包的局部加载问题，不阻止其他主题继续加载。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThemeIssue(
        String id,
        String code,
        String message,
        List<Integer> supportedVersions,
        Integer actualVersion
) {
    public ThemeIssue {
        supportedVersions = supportedVersions == null ? null : List.copyOf(supportedVersions);
    }

    public static ThemeIssue of(String id, String code, String message) {
        return new ThemeIssue(id, code, message, null, null);
    }
}
