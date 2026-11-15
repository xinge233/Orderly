package com.orderly.host.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

/** 解析 AI 直执动作参数中的 {@code $fromAction} 结构化引用。 */
public final class StructuredActionReferenceResolver {

    private StructuredActionReferenceResolver() {
    }

    public static Map<String, Object> resolve(
            Map<String, Object> arguments,
            List<Map<String, Object>> completedOutputs
    ) {
        Object resolved = resolveValue(arguments == null ? Map.of() : arguments, completedOutputs, "arguments");
        if (!(resolved instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("动作 arguments 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Collections.unmodifiableMap(result);
    }

    private static Object resolveValue(
            Object value,
            List<Map<String, Object>> completedOutputs,
            String location
    ) {
        if (value instanceof Map<?, ?> rawMap) {
            if (rawMap.containsKey("$fromAction")) {
                return resolveReference(rawMap, completedOutputs, location);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, child) -> result.put(
                    String.valueOf(key),
                    resolveValue(child, completedOutputs, location + "." + key)
            ));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                result.add(resolveValue(
                        list.get(index),
                        completedOutputs,
                        location + "[" + index + "]"
                ));
            }
            return List.copyOf(result);
        }
        return value;
    }

    private static Object resolveReference(
            Map<?, ?> reference,
            List<Map<String, Object>> completedOutputs,
            String location
    ) {
        if (reference.size() != 2 || !reference.containsKey("path")) {
            throw new IllegalArgumentException(
                    location + " 的 $fromAction 引用只能包含 $fromAction 和 path"
            );
        }
        Object rawIndex = reference.get("$fromAction");
        Integer actionIndex = strictIndex(rawIndex);
        if (actionIndex == null) {
            throw new IllegalArgumentException(location + ".$fromAction 必须是整数");
        }
        if (actionIndex < 0 || actionIndex >= completedOutputs.size()) {
            throw new IllegalArgumentException(
                    location + ".$fromAction 只能引用此前已完成的动作索引"
            );
        }
        Object rawPath = reference.get("path");
        if (!(rawPath instanceof List<?> path) || path.isEmpty()) {
            throw new IllegalArgumentException(location + ".path 必须是非空数组");
        }
        if (!"output".equals(path.getFirst())) {
            throw new IllegalArgumentException(location + ".path 必须以 output 开头");
        }
        Object current = completedOutputs.get(actionIndex);
        for (int index = 1; index < path.size(); index++) {
            Object segment = path.get(index);
            if (segment instanceof Number listPosition) {
                Integer strictListIndex = strictIndex(listPosition);
                if (strictListIndex == null) {
                    throw new IllegalArgumentException(
                            location + ".path 中的数组索引必须是整数"
                    );
                }
                int listIndex = strictListIndex;
                if (!(current instanceof List<?> list)
                        || listIndex < 0
                        || listIndex >= list.size()) {
                    throw new IllegalArgumentException(
                            location + ".path 在动作 " + actionIndex + " 的输出中无法解析"
                    );
                }
                current = list.get(listIndex);
                continue;
            }
            if (!(segment instanceof String key) || !(current instanceof Map<?, ?> map)
                    || !map.containsKey(key)) {
                throw new IllegalArgumentException(
                        location + ".path 在动作 " + actionIndex + " 的输出中无法解析"
                );
            }
            current = map.get(key);
        }
        return current;
    }

    private static Integer strictIndex(Object value) {
        if (value instanceof Byte number) {
            return number.intValue();
        }
        if (value instanceof Short number) {
            return number.intValue();
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                return null;
            }
            return number.intValue();
        }
        if (value instanceof BigInteger number) {
            if (number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return null;
            }
            return number.intValue();
        }
        return null;
    }
}
