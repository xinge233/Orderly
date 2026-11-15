package com.orderly.host.execution;

import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ParameterContract;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 契约参数的白名单、必填和基础类型校验。 */
public final class ActionArguments {

    private ActionArguments() {
    }

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
