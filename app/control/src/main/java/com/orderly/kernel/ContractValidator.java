package com.orderly.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates registry data against the frozen contract documents.
 *
 * Only the JSON Schema keywords used by the S1 contracts are implemented.
 * Extending the contracts therefore requires extending this validator and its
 * tests, while avoiding a second field list inside Java.
 */
public final class ContractValidator {
    private final JsonNode moduleSchema;
    private final JsonNode registrySchema;
    private final ObjectMapper objectMapper;

    private ContractValidator(JsonNode moduleSchema, JsonNode registrySchema, ObjectMapper objectMapper) {
        this.moduleSchema = moduleSchema;
        this.registrySchema = registrySchema;
        this.objectMapper = objectMapper;
    }

    public static ContractValidator load(Path contractsRoot) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode moduleSchema = mapper.readTree(Files.readString(contractsRoot.resolve("module.schema.json")));
        JsonNode registrySchema = mapper.readTree(Files.readString(contractsRoot.resolve("registry.schema.json")));
        ContractValidator validator = new ContractValidator(moduleSchema, registrySchema, mapper);
        validator.validateSchemaDocument();
        return validator;
    }

    public JsonNode read(Path path) throws IOException {
        return objectMapper.readTree(Files.readString(path));
    }

    public List<JsonNode> validateRegistry(JsonNode registry) {
        List<String> errors = new ArrayList<>();
        validateNode(registry, registrySchema, "", errors);
        Set<String> ids = new HashSet<>();
        JsonNode modules = registry.path("modules");
        if (modules.isArray()) {
            for (int index = 0; index < modules.size(); index++) {
                JsonNode id = modules.get(index).path("id");
                if (id.isTextual() && !ids.add(id.asText())) {
                    errors.add(pointer("/modules/" + index + "/id") + " -> duplicate module id: " + id.asText());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ContractValidationException("registry contract validation failed", errors);
        }
        List<JsonNode> result = new ArrayList<>();
        modules.forEach(result::add);
        return List.copyOf(result);
    }

    public void validateSchemaDocument() {
        List<String> errors = new ArrayList<>();
        requireSchemaDocument(moduleSchema, "module.schema.json", errors);
        requireSchemaDocument(registrySchema, "registry.schema.json", errors);
        if (!errors.isEmpty()) {
            throw new ContractValidationException("contract schema validation failed", errors);
        }
    }

    public void validateModule(JsonNode module) {
        List<String> errors = new ArrayList<>();
        validateNode(module, moduleSchema, "", errors);
        if (!errors.isEmpty()) {
            throw new ContractValidationException("module contract validation failed", errors);
        }
    }

    public void validateAgainstModuleSchema(JsonNode value) {
        validateModule(value);
    }

    private void requireSchemaDocument(JsonNode schema, String name, List<String> errors) {
        if (!schema.isObject()) {
            errors.add(pointer(name) + " -> schema must be a JSON object");
            return;
        }
        if (!"https://json-schema.org/draft/2020-12/schema".equals(schema.path("$schema").asText())) {
            errors.add(pointer(name + "/$schema") + " -> expected JSON Schema 2020-12");
        }
        if (!"object".equals(schema.path("type").asText())) {
            errors.add(pointer(name + "/type") + " -> root schema type must be object");
        }
        if (!schema.path("properties").isObject()) {
            errors.add(pointer(name + "/properties") + " -> properties must be an object");
        }
        if (!schema.path("required").isArray()) {
            errors.add(pointer(name + "/required") + " -> required must be an array");
        }
    }

    private void validateNode(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        if (schema.isBoolean()) {
            if (!schema.booleanValue()) {
                errors.add(pointer(path) + " -> schema rejects this value");
            }
            return;
        }
        if (!schema.isObject()) {
            errors.add(pointer(path) + " -> schema node must be an object");
            return;
        }

        JsonNode reference = schema.get("$ref");
        if (reference != null && reference.isTextual()) {
            JsonNode resolved = resolve(reference.asText());
            if (resolved == null) {
                errors.add(pointer(path) + " -> unresolved schema reference: " + reference.asText());
                return;
            }
            validateNode(value, resolved, path, errors);
        }

        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            errors.add(pointer(path) + " -> expected constant " + constant);
            return;
        }

        JsonNode allowed = schema.get("enum");
        if (allowed != null && allowed.isArray()) {
            boolean matched = false;
            for (JsonNode candidate : allowed) {
                if (candidate.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(pointer(path) + " -> value must be one of " + allowed);
                return;
            }
        }

        JsonNode type = schema.get("type");
        if (type != null && !matchesType(value, type)) {
            errors.add(pointer(path) + " -> expected type " + type + ", got " + actualType(value));
            return;
        }

        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            int matches = 0;
            List<String> branchErrors = new ArrayList<>();
            for (JsonNode branch : oneOf) {
                List<String> current = new ArrayList<>();
                validateNode(value, branch, path, current);
                if (current.isEmpty()) {
                    matches++;
                } else {
                    branchErrors.addAll(current);
                }
            }
            if (matches != 1) {
                errors.add(pointer(path) + " -> value must match exactly one oneOf branch");
                errors.addAll(branchErrors);
            }
        }

        if (value.isObject()) {
            validateObject(value, schema, path, errors);
        } else if (value.isArray()) {
            validateArray(value, schema, path, errors);
        } else if (value.isTextual()) {
            validateString(value, schema, path, errors);
        } else if (value.isNumber()) {
            validateNumber(value, schema, path, errors);
        }
    }

    private void validateObject(JsonNode value, JsonNode schema, String path, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (!value.has(field.asText())) {
                    errors.add(pointer(path + "/" + field.asText()) + " -> required field is missing");
                }
            }
        }

        JsonNode properties = schema.get("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (properties != null && properties.isObject()) {
                JsonNode propertySchema = properties.get(field.getKey());
                if (propertySchema != null) {
                    validateNode(field.getValue(), propertySchema, path + "/" + field.getKey(), errors);
                }
            }
        }

        JsonNode additional = schema.get("additionalProperties");
        if (additional != null) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (properties != null && properties.has(name)) {
                    continue;
                }
                if (additional.isBoolean() && !additional.booleanValue()) {
                    errors.add(pointer(path + "/" + name) + " -> additional property is not allowed");
                } else if (additional.isObject()) {
                    validateNode(value.get(name), additional, path + "/" + name, errors);
                }
            }
        }
    }

    private void validateArray(JsonNode value, JsonNode schema, String path, List<String> errors) {
        JsonNode minimum = schema.get("minItems");
        if (minimum != null && value.size() < minimum.asInt()) {
            errors.add(pointer(path) + " -> expected at least " + minimum.asInt() + " items");
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int index = 0; index < value.size(); index++) {
                validateNode(value.get(index), items, path + "/" + index, errors);
            }
        }
        JsonNode unique = schema.get("uniqueItems");
        if (unique != null && unique.asBoolean()) {
            Set<JsonNode> seen = new HashSet<>();
            for (JsonNode item : value) {
                if (!seen.add(item)) {
                    errors.add(pointer(path) + " -> items must be unique");
                    break;
                }
            }
        }
    }

    private void validateString(JsonNode value, JsonNode schema, String path, List<String> errors) {
        JsonNode minimum = schema.get("minLength");
        if (minimum != null && value.textValue().codePointCount(0, value.textValue().length()) < minimum.asInt()) {
            errors.add(pointer(path) + " -> string is shorter than " + minimum.asInt());
        }
        JsonNode maximum = schema.get("maxLength");
        if (maximum != null && value.textValue().codePointCount(0, value.textValue().length()) > maximum.asInt()) {
            errors.add(pointer(path) + " -> string is longer than " + maximum.asInt());
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            try {
                if (!Pattern.compile(pattern.asText()).matcher(value.textValue()).find()) {
                    errors.add(pointer(path) + " -> string does not match pattern " + pattern.asText());
                }
            } catch (RuntimeException invalidPattern) {
                errors.add(pointer(path) + " -> schema contains invalid pattern: " + invalidPattern.getMessage());
            }
        }
    }

    private void validateNumber(JsonNode value, JsonNode schema, String path, List<String> errors) {
        BigDecimal number = value.decimalValue();
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && number.compareTo(minimum.decimalValue()) < 0) {
            errors.add(pointer(path) + " -> number is less than minimum");
        }
        JsonNode exclusiveMinimum = schema.get("exclusiveMinimum");
        if (exclusiveMinimum != null && exclusiveMinimum.isNumber()
                && number.compareTo(exclusiveMinimum.decimalValue()) <= 0) {
            errors.add(pointer(path) + " -> number must be greater than exclusive minimum");
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null && number.compareTo(maximum.decimalValue()) > 0) {
            errors.add(pointer(path) + " -> number is greater than maximum");
        }
        JsonNode multipleOf = schema.get("multipleOf");
        if (multipleOf != null && multipleOf.decimalValue().signum() > 0
                && number.remainder(multipleOf.decimalValue()).compareTo(BigDecimal.ZERO) != 0) {
            errors.add(pointer(path) + " -> number is not a multiple of " + multipleOf);
        }
    }

    private boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isTextual()) {
            return matchesType(value, type.asText());
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && matchesType(value, candidate.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private String actualType(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isNull()) {
            return "null";
        }
        return value.getNodeType().toString().toLowerCase();
    }

    private JsonNode resolve(String reference) {
        if ("module.schema.json".equals(reference)) {
            return moduleSchema;
        }
        if ("registry.schema.json".equals(reference)) {
            return registrySchema;
        }
        if (!reference.startsWith("#/")) {
            return null;
        }
        JsonNode current = moduleSchema;
        for (String segment : reference.substring(2).split("/")) {
            current = current.path(segment.replace("~1", "/").replace("~0", "~"));
        }
        return current.isMissingNode() ? null : current;
    }

    private String pointer(String path) {
        return path == null || path.isEmpty() ? "/" : path;
    }
}
