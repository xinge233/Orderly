package com.orderly.host.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

/**
 * 解析有序 Action 参数中的 {@code $fromAction} 结构化引用。
 *
 * <p>引用只能指向当前批次中更早且已完成的输出，索引为零基。引用对象必须恰好包含
 * {@code $fromAction} 和 {@code path}，路径必须非空、以 {@code output} 开头，并逐段
 * 解析对象字段或数组索引；解析结果再次作为普通参数交给契约类型校验。</p>
 */
public final class StructuredActionReferenceResolver {

    private StructuredActionReferenceResolver() {
    }

    /**
     * 递归解析一个 Action 参数对象，并返回不可变顶层结果。
     *
     * <p>该方法无外部副作用；缺失、越界、非法路径或非法索引会抛出
     * {@link IllegalArgumentException}，由执行入口停止当前批次。</p>
     */
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

    /** 递归处理对象和数组，普通值原样返回，发现 {@code $fromAction} 时进入引用解析。 */
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

    /**
     * 从已完成输出中解析单个引用。
     *
     * <p>{@code $fromAction} 必须落在已完成输出范围内；路径首段必须是 output，
     * 后续段只允许命中现有对象字段或有效数组位置。</p>
     */
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

    /** 仅接受可无损转换为 int 的整数类型，拒绝小数、布尔和越界长整数。 */
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
