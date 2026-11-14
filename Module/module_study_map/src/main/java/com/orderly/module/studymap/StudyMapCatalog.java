package com.orderly.module.studymap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 读取并校验模块自身 classpath 内的高等数学知识图谱。 */
final class StudyMapCatalog {

    private static final String RESOURCE_PATH = "/orderly-data/module_study_map.json";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion",
            "kind",
            "ownerModule",
            "readOnly",
            "title",
            "description",
            "sources",
            "count",
            "payload"
    );
    private static final Set<String> SOURCE_FIELDS = Set.of("id", "label", "kind", "location");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("nodes", "edges");
    private static final Set<String> NODE_FIELDS = Set.of(
            "id",
            "label",
            "category",
            "courseId",
            "summary",
            "sourceIds",
            "layer",
            "order"
    );
    private static final Set<String> EDGE_FIELDS = Set.of(
            "id",
            "source",
            "target",
            "label",
            "kind"
    );
    private static final Set<String> SOURCE_KINDS = Set.of("module-data", "material", "note", "rule");
    private static final Set<String> NODE_CATEGORIES = Set.of("模块", "概念", "方法", "公式", "练习");
    private static final Set<String> EDGE_KINDS = Set.of("contains", "depends", "supports", "applies");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Object> cached;

    synchronized Map<String, Object> load() {
        if (cached != null) {
            return cached;
        }
        try (InputStream input = StudyMapCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("知识图谱数据资源不存在：" + RESOURCE_PATH);
            }
            Map<String, Object> document = objectMapper.readValue(input, MAP_TYPE);
            validate(document);
            cached = Map.copyOf(document);
            return cached;
        } catch (IOException error) {
            throw new IllegalStateException("读取模块知识图谱数据失败", error);
        }
    }

    private void validate(Map<String, Object> document) {
        requireExactFields(document, ROOT_FIELDS, "根节点");
        requireInteger(document, "schemaVersion", "根节点", 1);
        if (!"knowledge-map".equals(requireString(document, "kind", "根节点"))) {
            throw invalid("kind 必须为 knowledge-map");
        }
        if (!"module_study_map".equals(requireString(document, "ownerModule", "根节点"))) {
            throw invalid("ownerModule 必须为 module_study_map");
        }
        if (!Boolean.TRUE.equals(document.get("readOnly"))) {
            throw invalid("readOnly 必须为 true");
        }
        requireString(document, "title", "根节点");
        requireString(document, "description", "根节点");

        Set<String> sourceIds = validateSources(requireList(document, "sources", "根节点"));
        Map<String, Object> payload = requireObject(document.get("payload"), "payload");
        requireExactFields(payload, PAYLOAD_FIELDS, "payload");
        Set<String> nodeIds = validateNodes(
                requireList(payload, "nodes", "payload"),
                sourceIds
        );
        validateEdges(requireList(payload, "edges", "payload"), nodeIds);

        int count = requireInteger(document, "count", "根节点");
        if (count != nodeIds.size()) {
            throw invalid("count 必须与 nodes 数量一致");
        }
    }

    private Set<String> validateSources(List<?> sources) {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            String scope = "sources[" + index + "]";
            Map<String, Object> source = requireObject(sources.get(index), scope);
            requireExactFields(source, SOURCE_FIELDS, scope);
            requireUniqueId(source, scope, ids);
            requireString(source, "label", scope);
            String kind = requireString(source, "kind", scope);
            if (!SOURCE_KINDS.contains(kind)) {
                throw invalid(scope + ".kind 不符合契约");
            }
            requireString(source, "location", scope);
        }
        return ids;
    }

    private Set<String> validateNodes(List<?> nodes, Set<String> sourceIds) {
        if (nodes.isEmpty()) {
            throw invalid("payload.nodes 至少需要一个节点");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            String scope = "payload.nodes[" + index + "]";
            Map<String, Object> node = requireObject(nodes.get(index), scope);
            requireExactFields(node, NODE_FIELDS, scope);
            requireUniqueId(node, scope, ids);
            requireString(node, "label", scope);
            String category = requireString(node, "category", scope);
            if (!NODE_CATEGORIES.contains(category)) {
                throw invalid(scope + ".category 不符合契约");
            }
            requireString(node, "courseId", scope);
            requireString(node, "summary", scope);
            validateNodeSources(requireList(node, "sourceIds", scope), sourceIds, scope);
            int layer = requireInteger(node, "layer", scope);
            if (layer > 5) {
                throw invalid(scope + ".layer 必须在 0 到 5 之间");
            }
            requireInteger(node, "order", scope);
        }
        return ids;
    }

    private void validateNodeSources(List<?> sourceIds, Set<String> availableSources, String scope) {
        for (int index = 0; index < sourceIds.size(); index++) {
            Object raw = sourceIds.get(index);
            if (!(raw instanceof String sourceId) || sourceId.isBlank()) {
                throw invalid(scope + ".sourceIds[" + index + "] 必须是非空字符串");
            }
            if (!availableSources.contains(sourceId)) {
                throw invalid(scope + ".sourceIds 引用了不存在的来源：" + sourceId);
            }
        }
    }

    private void validateEdges(List<?> edges, Set<String> nodeIds) {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < edges.size(); index++) {
            String scope = "payload.edges[" + index + "]";
            Map<String, Object> edge = requireObject(edges.get(index), scope);
            requireExactFields(edge, EDGE_FIELDS, scope);
            requireUniqueId(edge, scope, ids);
            requireReference(edge, "source", nodeIds, scope);
            requireReference(edge, "target", nodeIds, scope);
            requireString(edge, "label", scope);
            String kind = requireString(edge, "kind", scope);
            if (!EDGE_KINDS.contains(kind)) {
                throw invalid(scope + ".kind 不符合契约");
            }
        }
    }

    private void requireReference(
            Map<String, Object> value,
            String field,
            Set<String> candidates,
            String scope
    ) {
        String reference = requireString(value, field, scope);
        if (!candidates.contains(reference)) {
            throw invalid(scope + "." + field + " 引用了不存在的对象：" + reference);
        }
    }

    private void requireUniqueId(Map<String, Object> value, String scope, Set<String> ids) {
        String id = requireString(value, "id", scope);
        if (!ids.add(id)) {
            throw invalid(scope + ".id 重复：" + id);
        }
    }

    private void requireExactFields(
            Map<String, Object> value,
            Set<String> expected,
            String scope
    ) {
        if (!value.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(value.keySet());
            Set<String> unexpected = new HashSet<>(value.keySet());
            unexpected.removeAll(expected);
            throw invalid(scope + " 字段不符合契约；缺少 " + missing + "，多出 " + unexpected);
        }
    }

    private String requireString(Map<String, Object> value, String field, String scope) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(scope + "." + field + " 必须是非空字符串");
        }
        return text;
    }

    private int requireInteger(Map<String, Object> value, String field, String scope) {
        Object raw = value.get(field);
        if (!(raw instanceof Integer number) || number < 0) {
            throw invalid(scope + "." + field + " 必须是非负整数");
        }
        return number;
    }

    private void requireInteger(
            Map<String, Object> value,
            String field,
            String scope,
            int expected
    ) {
        if (requireInteger(value, field, scope) != expected) {
            throw invalid(scope + "." + field + " 必须为 " + expected);
        }
    }

    private List<?> requireList(Map<String, Object> value, String field, String scope) {
        return requireList(value.get(field), scope + "." + field);
    }

    private List<?> requireList(Object raw, String scope) {
        if (!(raw instanceof List<?> list)) {
            throw invalid(scope + " 必须是数组");
        }
        return list;
    }

    private Map<String, Object> requireObject(Object raw, String scope) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(scope + " 必须是对象");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw invalid(scope + " 包含非字符串字段名");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private IllegalStateException invalid(String detail) {
        return new IllegalStateException("知识图谱数据不符合 knowledge-map 契约：" + detail);
    }
}
