package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个已通过启动校验的主题包内部定义。
 *
 * <p>资源列表在构造时复制，根路径规范化为绝对路径并标记为 JSON 忽略字段，避免通过
 * 主题 API 泄露宿主机目录。该定义只供宿主资源读取和清单投影，不包含主题文件内容。</p>
 *
 * @param schemaVersion 已支持的清单版本
 * @param id 目录与清单一致的稳定 ID
 * @param label 显示名称
 * @param note 可选说明
 * @param order 排序值
 * @param defaultTheme 是否为默认主题
 * @param colorScheme 可选颜色方案
 * @param browserColor 可选浏览器颜色
 * @param styles 已登记且存在的 CSS 路径
 * @param assets 已登记且存在的静态资源路径
 * @param rootPath 宿主内部主题根路径
 */
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
    /** 复制资源列表并规范化内部根路径；不再次访问文件系统。 */
    public ThemeDefinition {
        styles = List.copyOf(styles);
        assets = List.copyOf(assets);
        rootPath = rootPath.toAbsolutePath().normalize();
    }

    /** 投影为不含资源路径和磁盘根目录的列表摘要。 */
    public ThemeSummary summary() {
        return new ThemeSummary(schemaVersion, id, label, note, order, defaultTheme);
    }
}
