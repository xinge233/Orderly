package com.orderly.host.theme;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个主题包加载失败的局部问题。
 *
 * <p>问题用于主题列表诊断，不包含宿主机绝对路径。版本不匹配时可携带程序支持版本和
 * 实际版本；其他问题对应字段为空并通过 JSON 省略。</p>
 *
 * @param id 目录或主题标识
 * @param code 稳定机器错误码
 * @param message 面向诊断的说明
 * @param supportedVersions 支持的清单版本
 * @param actualVersion 检测到的版本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThemeIssue(
        String id,
        String code,
        String message,
        List<Integer> supportedVersions,
        Integer actualVersion
) {
    /** 复制可选支持版本列表；不修改已生成的问题文本或错误码。 */
    public ThemeIssue {
        supportedVersions = supportedVersions == null ? null : List.copyOf(supportedVersions);
    }

    /** 创建不含版本详情的普通局部问题。 */
    public static ThemeIssue of(String id, String code, String message) {
        return new ThemeIssue(id, code, message, null, null);
    }
}
