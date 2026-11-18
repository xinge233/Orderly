package com.orderly.module.studymap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code module_study_map} 自带知识图谱的只读目录。
 *
 * <p>本类只读取本模块 JAR 内的 {@code orderly-data/module_study_map.json}，校验数据归属、只读
 * 标记、节点来源、边端点与 ID 唯一性。它不访问工作区、其他模块集合、任意个人目录或宿主
 * 数据库，也不提供写入、删除、快照或回滚能力。校验失败必须阻止展示，不能静默丢弃坏节点。</p>
 */
final class StudyMapCatalog {

    /** 仅属于本模块 JAR 的固定知识图谱资源。 */
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

    /** 本目录独占的 JSON 解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 只在完整校验成功后保存的只读文档缓存。 */
    private Map<String, Object> cached;

    /**
     * 加载并校验知识图谱文档。
     *
     * <p>首次成功后缓存根文档，后续查询复用同一只读数据。资源缺失、解析失败或结构不符合契约
     * 时抛出明确异常，不退回空图、其他模块数据或工作区文件。</p>
     */
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

    /** 校验根元信息、来源、节点、边及声明节点数量的一致性。 */
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

    /** 校验来源字段、允许类型和 ID 唯一性，返回节点可引用的来源集合。 */
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

    /**
     * 校验节点字段、分类、层级、顺序、ID 唯一性和来源引用。
     *
     * <p>节点必须位于同一文档声明的来源范围内，且至少存在一个节点，防止空知识图谱被当作有效
     * 内容展示。</p>
     */
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

    /** 校验节点引用的来源 ID 均为非空且已在根文档声明。 */
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

    /**
     * 校验边的 ID、允许类型以及源/目标节点引用。
     *
     * <p>边只能连接本文档已声明的节点，防止悬空关系或跨模块节点混入。</p>
     */
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

    /** 验证节点或边引用存在，并把错误限定到当前对象字段。 */
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

    /** 注册对象 ID；重复时拒绝整份知识图谱。 */
    private void requireUniqueId(Map<String, Object> value, String scope, Set<String> ids) {
        String id = requireString(value, "id", scope);
        if (!ids.add(id)) {
            throw invalid(scope + ".id 重复：" + id);
        }
    }

    /**
     * 要求对象字段集合与契约完全一致。
     *
     * <p>错误信息分别列出缺失和多余字段，避免未知字段被静默忽略。</p>
     */
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

    /** 要求字段为非空白字符串，并返回原值供引用和枚举校验。 */
    private String requireString(Map<String, Object> value, String field, String scope) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(scope + "." + field + " 必须是非空字符串");
        }
        return text;
    }

    /** 要求字段为非负整数。 */
    private int requireInteger(Map<String, Object> value, String field, String scope) {
        Object raw = value.get(field);
        if (!(raw instanceof Integer number) || number < 0) {
            throw invalid(scope + "." + field + " 必须是非负整数");
        }
        return number;
    }

    /** 要求整数字段等于当前文档版本或固定值。 */
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

    /** 从对象字段取得数组。 */
    private List<?> requireList(Map<String, Object> value, String field, String scope) {
        return requireList(value.get(field), scope + "." + field);
    }

    /** 要求原值为数组。 */
    private List<?> requireList(Object raw, String scope) {
        if (!(raw instanceof List<?> list)) {
            throw invalid(scope + " 必须是数组");
        }
        return list;
    }

    /**
     * 要求原值为字段名均为字符串的对象。
     *
     * <p>转换前显式检查键类型，避免后续按字符串字段名读取时发生隐式转换。</p>
     */
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

    /** 构造带 knowledge-map 契约前缀的数据错误，交由查询调用方报告给宿主。 */
    private IllegalStateException invalid(String detail) {
        return new IllegalStateException("知识图谱数据不符合 knowledge-map 契约：" + detail);
    }
}
