package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 宿主支持的输出 JSON Schema 子集。
 *
 * <p>允许的关键字为 {@code type}、{@code enum}、{@code properties}、
 * {@code required}、{@code items} 和 {@code additionalProperties}。
 * 复杂引用、组合关键字和条件关键字不在首版范围内。</p>
 */
public final class OutputSchemaValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null"
    );
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "enum", "properties", "required", "items", "additionalProperties"
    );

    private OutputSchemaValidator() {
    }

    /**
     * 在模块加载阶段校验动作声明的输出 schema 只使用受支持子集。
     */
    public static void validateDefinition(JsonNode schema) {
        validateDefinition(schema, "outputSchema");
    }

    /**
     * 在执行成功后校验动作输出是否符合其契约 schema。
     *
     * <p>未声明 schema 的动作不额外限制；校验失败会转换为包含动作 ID 的宿主异常，
     * 由执行核心记录失败并触发既有回滚流程。</p>
     */
    public static void validateOutput(
            JsonNode schema,
            Map<String, Object> output,
            ObjectMapper objectMapper,
            String actionId
    ) {
        validateOutput(schema, output, objectMapper, actionId, "动作");
    }

    /** 以调用方类型生成明确错误，供 Action 与 Query 共用。 */
    public static void validateOutput(
            JsonNode schema,
            Map<String, Object> output,
            ObjectMapper objectMapper,
            String ownerId,
            String ownerKind
    ) {
        if (schema == null || schema.isNull()) {
            return;
        }
        try {
            validateNode(schema, objectMapper.valueToTree(output), "output");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    ownerKind + " " + ownerId + " 输出不符合 outputSchema："
                            + exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * 递归检查 schema 关键字、类型和子节点结构。
     *
     * <p>首版有意拒绝 {@code $ref}、组合和条件关键字，保持跨语言校验范围可预测。</p>
     */
    private static void validateDefinition(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException(path + " 必须是对象");
        }
        Iterator<String> fields = schema.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!SUPPORTED_KEYWORDS.contains(field)) {
                throw new IllegalStateException(path + " 包含不支持的 JSON Schema 关键字：" + field);
            }
        }
        JsonNode rawType = schema.get("type");
        if (rawType != null && (!rawType.isTextual() || !SUPPORTED_TYPES.contains(rawType.asText()))) {
            throw new IllegalStateException(path + ".type 不是支持的 JSON 类型");
        }
        JsonNode rawEnum = schema.get("enum");
        if (rawEnum != null && (!rawEnum.isArray() || rawEnum.isEmpty())) {
            throw new IllegalStateException(path + ".enum 必须是非空数组");
        }
        JsonNode rawProperties = schema.get("properties");
        if (rawProperties != null) {
            if (!rawProperties.isObject()) {
                throw new IllegalStateException(path + ".properties 必须是对象");
            }
            rawProperties.fields().forEachRemaining(entry ->
                    validateDefinition(entry.getValue(), path + ".properties." + entry.getKey())
            );
        }
        JsonNode rawRequired = schema.get("required");
        if (rawRequired != null) {
            if (!rawRequired.isArray()) {
                throw new IllegalStateException(path + ".required 必须是字符串数组");
            }
            Set<String> unique = new HashSet<>();
            for (JsonNode item : rawRequired) {
                if (!item.isTextual() || item.asText().isBlank() || !unique.add(item.asText())) {
                    throw new IllegalStateException(path + ".required 必须是唯一非空字符串数组");
                }
            }
        }
        JsonNode rawItems = schema.get("items");
        if (rawItems != null) {
            validateDefinition(rawItems, path + ".items");
        }
        JsonNode rawAdditional = schema.get("additionalProperties");
        if (rawAdditional != null && !rawAdditional.isBoolean()) {
            validateDefinition(rawAdditional, path + ".additionalProperties");
        }
    }

    /**
     * 按 schema 递归校验一个输出值，包括 required、properties、
     * additionalProperties、items 和 enum。
     */
    private static void validateNode(JsonNode schema, JsonNode value, String path) {
        JsonNode rawType = schema.get("type");
        if (rawType != null && !matchesType(rawType.asText(), value)) {
            throw new IllegalArgumentException(path + " 类型应为 " + rawType.asText());
        }
        JsonNode rawEnum = schema.get("enum");
        if (rawEnum != null) {
            boolean matched = false;
            for (JsonNode candidate : rawEnum) {
                if (candidate.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new IllegalArgumentException(path + " 不在 enum 允许范围内");
            }
        }
        if (value.isObject()) {
            JsonNode rawRequired = schema.get("required");
            if (rawRequired != null) {
                for (JsonNode required : rawRequired) {
                    if (!value.has(required.asText())) {
                        throw new IllegalArgumentException(path + " 缺少字段 " + required.asText());
                    }
                }
            }
            JsonNode rawProperties = schema.get("properties");
            if (rawProperties != null) {
                rawProperties.fields().forEachRemaining(entry -> {
                    if (value.has(entry.getKey())) {
                        validateNode(entry.getValue(), value.get(entry.getKey()), path + "." + entry.getKey());
                    }
                });
            }
            JsonNode rawAdditional = schema.get("additionalProperties");
            if (rawAdditional != null) {
                value.fields().forEachRemaining(entry -> {
                    boolean declared = rawProperties != null && rawProperties.has(entry.getKey());
                    if (declared) {
                        return;
                    }
                    if (rawAdditional.isBoolean() && !rawAdditional.asBoolean()) {
                        throw new IllegalArgumentException(path + " 包含未声明字段 " + entry.getKey());
                    }
                    if (rawAdditional.isObject()) {
                        validateNode(rawAdditional, entry.getValue(), path + "." + entry.getKey());
                    }
                });
            }
        }
        if (value.isArray() && schema.has("items")) {
            JsonNode items = schema.get("items");
            for (int index = 0; index < value.size(); index++) {
                validateNode(items, value.get(index), path + "[" + index + "]");
            }
        }
    }

    /** 判断 JSON 节点是否匹配受支持的单一基础类型。 */
    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
