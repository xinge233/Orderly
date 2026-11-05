package com.orderly.platform.module;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单个动作参数的受控描述。
 *
 * <p>该结构等价于 Action 参数 JSON Schema 中的一项 property，明确类型、
 * 是否必填和可读说明，不保存任何执行逻辑。</p>
 *
 * @param name 参数名
 * @param description 面向用户的说明
 * @param type 允许的值类型
 * @param required 是否必须提供
 */
public record ActionParameterDefinition(
        String name,
        String description,
        ActionParameterType type,
        boolean required
) {
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-z][a-zA-Z0-9_]{0,63}");

    public ActionParameterDefinition {
        name = Objects.requireNonNullElse(name, "").trim();
        description = Objects.requireNonNullElse(description, "").trim();
        type = Objects.requireNonNull(type, "参数类型不能为空");

        if (!PARAMETER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法参数名：" + name);
        }
    }
}
