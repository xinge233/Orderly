package com.orderly.platform.api;

import java.util.List;

/**
 * 动作参数的公共 JSON Schema 等价结构。
 *
 * @param type schema 类型，固定为 object
 * @param properties 参数属性
 * @param required 必填参数名
 * @param additionalProperties 是否允许额外参数
 */
public record ParameterSchemaResponse(
        String type,
        List<ParameterPropertyResponse> properties,
        List<String> required,
        boolean additionalProperties
) {
    public ParameterSchemaResponse {
        properties = List.copyOf(properties);
        required = List.copyOf(required);
    }
}
