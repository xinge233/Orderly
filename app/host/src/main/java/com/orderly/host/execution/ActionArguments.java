package com.orderly.host.execution;

import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ParameterContract;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 按动作契约校验一次调用参数。
 *
 * <p>校验只使用契约声明的参数名和基础类型，不接受额外字段；它不执行动作，
 * 也不判断风险、确认或 AI 可调用性。</p>
 */
public final class ActionArguments {

    private ActionArguments() {
    }

    /**
     * 校验参数白名单、必填项和基础类型。
     *
     * @throws IllegalArgumentException 缺少必填参数、出现契约外参数或类型不符
     */
    public static void validate(ActionContract action, Map<String, Object> arguments) {
        Map<String, Object> suppliedArguments = arguments == null ? Map.of() : arguments;
        Set<String> allowed = new HashSet<>();
        for (ParameterContract parameter : action.parameters()) {
            allowed.add(parameter.name());
            Object value = suppliedArguments.get(parameter.name());
            if (parameter.required() && (value == null || value.toString().isBlank())) {
                throw new IllegalArgumentException(
                        "动作 " + action.id() + " 缺少必填参数：" + parameter.name()
                );
            }
            if (value != null) {
                validateType(action.id(), parameter, value);
            }
        }
        for (String supplied : suppliedArguments.keySet()) {
            if (!allowed.contains(supplied)) {
                throw new IllegalArgumentException(
                        "动作 " + action.id() + " 包含契约外参数：" + supplied
                );
            }
        }
    }

    /**
     * 校验契约支持的 string、boolean 和 integer 基础类型；整数仅接受 JVM 整型包装类型。
     */
    private static void validateType(
            String actionId,
            ParameterContract parameter,
            Object value
    ) {
        boolean valid = switch (parameter.type()) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "动作 " + actionId + " 参数 " + parameter.name()
                            + " 类型应为 " + parameter.type()
            );
        }
    }
}
