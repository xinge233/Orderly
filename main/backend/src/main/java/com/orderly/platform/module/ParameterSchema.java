package com.orderly.platform.module;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 动作参数的等价 JSON Schema 描述与本地校验器。
 *
 * <p>公开契约会把它映射为标准 object schema；执行管线在调用模块前也会使用
 * 同一个模型校验参数。未知参数、缺失必填项和类型错误都会在执行前被拒绝。</p>
 *
 * @param properties 参数定义列表
 */
public record ParameterSchema(List<ActionParameterDefinition> properties) {

    public ParameterSchema {
        properties = List.copyOf(Objects.requireNonNullElse(properties, List.of()));

        Set<String> names = new HashSet<>();
        for (ActionParameterDefinition property : properties) {
            if (!names.add(property.name())) {
                throw new IllegalArgumentException("参数名重复：" + property.name());
            }
        }
    }

    /**
     * 创建一个不接收参数的空对象 schema。
     *
     * @return 空参数 schema
     */
    public static ParameterSchema empty() {
        return new ParameterSchema(List.of());
    }

    /**
     * 校验参数集合并返回全部可读错误。
     *
     * <p>返回值而不是抛异常，便于中央管线统一生成拒绝结果和审计记录。</p>
     *
     * @param values 调用方提供的参数
     * @return 空列表表示合法，否则包含所有校验错误
     */
    public List<String> validate(Map<String, Object> values) {
        Map<String, Object> safeValues = Objects.requireNonNullElse(values, Map.of());
        List<String> errors = new java.util.ArrayList<>();
        Map<String, ActionParameterDefinition> definitions = properties.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ActionParameterDefinition::name,
                        property -> property
                ));

        for (ActionParameterDefinition definition : properties) {
            if (definition.required() && !safeValues.containsKey(definition.name())) {
                errors.add("缺少必填参数：" + definition.name());
            }
        }

        for (Map.Entry<String, Object> entry : safeValues.entrySet()) {
            ActionParameterDefinition definition = definitions.get(entry.getKey());
            if (definition == null) {
                errors.add("未知参数：" + entry.getKey());
                continue;
            }
            if (!matchesType(definition.type(), entry.getValue())) {
                errors.add("参数类型错误：" + entry.getKey());
            }
        }

        return List.copyOf(errors);
    }

    /**
     * 按受控类型检查 Java 值。
     *
     * <p>JSON 小数不会被自动转换为整数，以免截断造成不可预期的资源标识。</p>
     *
     * @param type 参数类型
     * @param value 待检查值
     * @return 类型匹配时返回 true
     */
    private boolean matchesType(ActionParameterType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer
                    || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case BOOLEAN -> value instanceof Boolean;
        };
    }
}
