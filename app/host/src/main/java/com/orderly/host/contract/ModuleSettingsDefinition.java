package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 模块 UI manifest 中声明的设置字段及宿主持有的统一校验逻辑。 */
public record ModuleSettingsDefinition(List<Field> fields) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    public ModuleSettingsDefinition {
        fields = List.copyOf(fields);
    }

    public static ModuleSettingsDefinition empty() {
        return new ModuleSettingsDefinition(List.of());
    }

    public static ModuleSettingsDefinition parse(JsonNode rawSettings, ObjectMapper objectMapper) {
        if (rawSettings == null || rawSettings.isMissingNode() || rawSettings.isNull()) {
            return empty();
        }
        JsonNode rawFields = rawSettings.isArray()
                ? rawSettings
                : rawSettings.path("fields");
        if (!rawFields.isArray()) {
            throw new IllegalStateException("模块 UI settings 必须是字段数组或包含 fields 的对象");
        }

        List<Field> fields = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode rawField : rawFields) {
            Field field = parseField(rawField, objectMapper);
            if (!keys.add(field.key())) {
                throw new IllegalStateException("模块 UI 设置字段 key 重复：" + field.key());
            }
            fields.add(field);
        }
        return new ModuleSettingsDefinition(fields);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Map<String, Object> defaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : fields) {
            values.put(field.key(), primitive(field.defaultValue()));
        }
        return Map.copyOf(values);
    }

    /**
     * 校验部分设置对象并与当前值合并，返回完整的有效设置。
     */
    public Map<String, Object> validateAndMerge(
            JsonNode supplied,
            Map<String, Object> current,
            ObjectMapper objectMapper
    ) {
        if (!supplied.isObject()) {
            throw new IllegalArgumentException("模块设置必须是 JSON 对象");
        }
        Map<String, Field> fieldsByKey = new LinkedHashMap<>();
        fields.forEach(field -> fieldsByKey.put(field.key(), field));
        for (var iterator = supplied.fields(); iterator.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (!fieldsByKey.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("模块设置包含未声明字段：" + entry.getKey());
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        for (Field field : fields) {
            JsonNode candidate = supplied.get(field.key());
            if (candidate != null) {
                validateValue(field, candidate);
                merged.put(field.key(), primitive(candidate));
            } else if (current.containsKey(field.key())) {
                merged.put(field.key(), current.get(field.key()));
            } else {
                merged.put(field.key(), primitive(field.defaultValue()));
            }
        }
        return Map.copyOf(merged);
    }

    private static Field parseField(JsonNode raw, ObjectMapper objectMapper) {
        requireText(raw, "key");
        requireText(raw, "type");
        String label = raw.path("label").isTextual() ? raw.path("label").asText() : raw.path("title").asText("");
        if (label.isBlank()) {
            throw new IllegalStateException("模块 UI 设置字段缺少 label 或 title");
        }
        String key = raw.path("key").asText();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalStateException("模块 UI 设置字段 key 非法：" + key);
        }
        if (!raw.has("default")) {
            throw new IllegalStateException("模块 UI 设置字段缺少 default：" + key);
        }

        String type = raw.path("type").asText();
        return switch (type) {
            case "boolean" -> parseBoolean(key, label, raw, objectMapper);
            case "integer" -> parseInteger(key, label, raw, objectMapper);
            case "select" -> parseSelect(key, label, raw, objectMapper);
            case "text" -> parseText(key, label, raw, objectMapper);
            default -> throw new IllegalStateException("不支持的设置字段类型：" + type);
        };
    }

    private static Field parseBoolean(
            String key,
            String label,
            JsonNode raw,
            ObjectMapper objectMapper
    ) {
        JsonNode defaultValue = raw.get("default");
        if (!defaultValue.isBoolean()) {
            throw new IllegalStateException("boolean 设置默认值类型错误：" + key);
        }
        return new Field(
                key,
                "boolean",
                label,
                raw.path("description").asText(""),
                defaultValue,
                List.of(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Field parseInteger(
            String key,
            String label,
            JsonNode raw,
            ObjectMapper objectMapper
    ) {
        JsonNode defaultValue = raw.get("default");
        if (!defaultValue.isIntegralNumber() || !defaultValue.canConvertToInt()) {
            throw new IllegalStateException("integer 设置默认值类型错误：" + key);
        }
        Integer minimum = optionalInteger(raw, "min", key);
        Integer maximum = optionalInteger(raw, "max", key);
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalStateException("integer 设置 min 不能大于 max：" + key);
        }
        int value = defaultValue.asInt();
        if ((minimum != null && value < minimum) || (maximum != null && value > maximum)) {
            throw new IllegalStateException("integer 设置默认值超出范围：" + key);
        }
        return new Field(
                key,
                "integer",
                label,
                raw.path("description").asText(""),
                defaultValue,
                List.of(),
                minimum,
                maximum,
                null,
                null,
                null
        );
    }

    private static Field parseSelect(
            String key,
            String label,
            JsonNode raw,
            ObjectMapper objectMapper
    ) {
        JsonNode defaultNode = raw.get("default");
        if (!defaultNode.isTextual() || defaultNode.asText().isBlank()) {
            throw new IllegalStateException("select 设置默认值必须是非空文本：" + key);
        }
        JsonNode rawOptions = raw.path("options");
        if (!rawOptions.isArray() || rawOptions.isEmpty()) {
            throw new IllegalStateException("select 设置 options 必须是非空数组：" + key);
        }
        List<Option> options = new ArrayList<>();
        Set<String> values = new HashSet<>();
        for (JsonNode rawOption : rawOptions) {
            requireText(rawOption, "value");
            requireText(rawOption, "label");
            String value = rawOption.path("value").asText();
            if (!values.add(value)) {
                throw new IllegalStateException("select 设置 option value 重复：" + key + "/" + value);
            }
            options.add(new Option(value, rawOption.path("label").asText()));
        }
        String defaultValue = defaultNode.asText();
        if (!values.contains(defaultValue)) {
            throw new IllegalStateException("select 设置默认值不在 options 中：" + key);
        }
        return new Field(
                key,
                "select",
                label,
                raw.path("description").asText(""),
                defaultNode,
                options,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Field parseText(
            String key,
            String label,
            JsonNode raw,
            ObjectMapper objectMapper
    ) {
        JsonNode defaultValue = raw.get("default");
        if (!defaultValue.isTextual()) {
            throw new IllegalStateException("text 设置默认值类型错误：" + key);
        }
        Integer minimum = optionalInteger(raw, "minLength", key);
        Integer maximum = optionalInteger(raw, "maxLength", key);
        if (minimum != null && minimum < 0) {
            throw new IllegalStateException("text 设置 minLength 不能小于 0：" + key);
        }
        if (maximum != null && maximum < 1) {
            throw new IllegalStateException("text 设置 maxLength 必须大于 0：" + key);
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalStateException("text 设置 minLength 不能大于 maxLength：" + key);
        }
        int length = defaultValue.asText().length();
        if ((minimum != null && length < minimum) || (maximum != null && length > maximum)) {
            throw new IllegalStateException("text 设置默认值长度超出范围：" + key);
        }
        return new Field(
                key,
                "text",
                label,
                raw.path("description").asText(""),
                defaultValue,
                List.of(),
                null,
                null,
                raw.path("placeholder").isTextual() ? raw.path("placeholder").asText() : null,
                minimum,
                maximum
        );
    }

    private static void validateValue(Field field, JsonNode value) {
        switch (field.type()) {
            case "boolean" -> {
                if (!value.isBoolean()) {
                    throw invalidType(field);
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    throw invalidType(field);
                }
                int number = value.asInt();
                if ((field.minimum() != null && number < field.minimum())
                        || (field.maximum() != null && number > field.maximum())) {
                    throw new IllegalArgumentException("模块设置字段超出范围：" + field.key());
                }
            }
            case "select" -> {
                if (!value.isTextual()
                        || field.options().stream().noneMatch(option -> option.value().equals(value.asText()))) {
                    throw new IllegalArgumentException("模块设置字段值不在 options 中：" + field.key());
                }
            }
            case "text" -> {
                if (!value.isTextual()) {
                    throw invalidType(field);
                }
                int length = value.asText().length();
                if ((field.minimum() != null && length < field.minimum())
                        || (field.maximum() != null && length > field.maximum())) {
                    throw new IllegalArgumentException("模块设置文本长度超出范围：" + field.key());
                }
            }
            default -> throw new IllegalStateException("未知设置字段类型：" + field.type());
        }
    }

    private static IllegalArgumentException invalidType(Field field) {
        return new IllegalArgumentException(
                "模块设置字段 " + field.key() + " 类型应为 " + field.type()
        );
    }

    private static Object primitive(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asInt();
        }
        return value.asText();
    }

    private static Integer optionalInteger(JsonNode raw, String field, String key) {
        JsonNode value = raw.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("设置字段 " + field + " 必须是整数：" + key);
        }
        return value.asInt();
    }

    private static void requireText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
            throw new IllegalStateException("模块 UI 设置缺少文本字段：" + field);
        }
    }

    public record Field(
            String key,
            String type,
            String label,
            String description,
            JsonNode defaultValue,
            List<Option> options,
            Integer minimum,
            Integer maximum,
            String placeholder,
            Integer minLength,
            Integer maxLength
    ) {
        public Field {
            options = List.copyOf(options);
        }
    }

    public record Option(String value, String label) {
    }
}
