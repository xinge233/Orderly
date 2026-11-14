package com.orderly.module.fs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取模块自带的学习资料只读数据。
 *
 * <p>数据文件固定在模块 classpath 内，查询不会访问工作区或个人目录。</p>
 */
final class StudyMaterialsCatalog {

    private static final String RESOURCE_PATH = "/orderly-data/module_fs.json";
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
    private static final Set<String> PAYLOAD_FIELDS = Set.of("courses", "materials");
    private static final Set<String> COURSE_FIELDS = Set.of("id", "title", "code", "description");
    private static final Set<String> MATERIAL_FIELDS = Set.of(
            "id",
            "title",
            "courseId",
            "chapter",
            "format",
            "status",
            "sourceId",
            "excerpt",
            "cards"
    );
    private static final Set<String> CARD_FIELDS = Set.of("id", "title", "summary");
    private static final Set<String> SOURCE_KINDS = Set.of("module-data", "material", "note", "rule");
    private static final Set<String> MATERIAL_FORMATS = Set.of("PDF", "Markdown", "HTML", "DOCX");
    private static final Set<String> MATERIAL_STATUSES = Set.of("已整理", "待复核", "只读示例");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Object> cached;

    synchronized Map<String, Object> load() {
        if (cached != null) {
            return cached;
        }
        try (InputStream input = StudyMaterialsCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("资料数据资源不存在：" + RESOURCE_PATH);
            }
            Map<String, Object> document = objectMapper.readValue(input, MAP_TYPE);
            validate(document);
            cached = Map.copyOf(document);
            return cached;
        } catch (IOException error) {
            throw new IllegalStateException("读取模块资料数据失败", error);
        }
    }

    private void validate(Map<String, Object> document) {
        requireExactFields(document, ROOT_FIELDS, "根节点");
        requireInteger(document, "schemaVersion", "根节点", 1);
        requireString(document, "kind", "根节点");
        if (!"materials".equals(document.get("kind"))) {
            throw invalid("kind 必须为 materials");
        }
        if (!"module_fs".equals(requireString(document, "ownerModule", "根节点"))) {
            throw invalid("ownerModule 必须为 module_fs");
        }
        if (!Boolean.TRUE.equals(document.get("readOnly"))) {
            throw invalid("readOnly 必须为 true");
        }
        requireString(document, "title", "根节点");
        requireString(document, "description", "根节点");

        Set<String> sourceIds = validateSources(requireList(document, "sources", "根节点"));
        Map<String, Object> payload = requireObject(document.get("payload"), "payload");
        requireExactFields(payload, PAYLOAD_FIELDS, "payload");
        Set<String> courseIds = validateCourses(requireList(payload, "courses", "payload"));
        List<?> materials = requireList(payload, "materials", "payload");
        validateMaterials(materials, courseIds, sourceIds);

        int count = requireInteger(document, "count", "根节点");
        if (count != materials.size()) {
            throw invalid("count 必须与 materials 数量一致");
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

    private Set<String> validateCourses(List<?> courses) {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < courses.size(); index++) {
            String scope = "payload.courses[" + index + "]";
            Map<String, Object> course = requireObject(courses.get(index), scope);
            requireExactFields(course, COURSE_FIELDS, scope);
            requireUniqueId(course, scope, ids);
            requireString(course, "title", scope);
            requireString(course, "code", scope);
            requireString(course, "description", scope);
        }
        return ids;
    }

    private void validateMaterials(
            List<?> materials,
            Set<String> courseIds,
            Set<String> sourceIds
    ) {
        Set<String> materialIds = new HashSet<>();
        for (int index = 0; index < materials.size(); index++) {
            String scope = "payload.materials[" + index + "]";
            Map<String, Object> material = requireObject(materials.get(index), scope);
            requireExactFields(material, MATERIAL_FIELDS, scope);
            requireUniqueId(material, scope, materialIds);
            requireString(material, "title", scope);
            requireReference(material, "courseId", courseIds, scope);
            requireString(material, "chapter", scope);
            String format = requireString(material, "format", scope);
            if (!MATERIAL_FORMATS.contains(format)) {
                throw invalid(scope + ".format 不符合契约");
            }
            String status = requireString(material, "status", scope);
            if (!MATERIAL_STATUSES.contains(status)) {
                throw invalid(scope + ".status 不符合契约");
            }
            requireReference(material, "sourceId", sourceIds, scope);
            requireString(material, "excerpt", scope);
            validateCards(requireList(material, "cards", scope), scope);
        }
    }

    private void validateCards(List<?> cards, String materialScope) {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < cards.size(); index++) {
            String scope = materialScope + ".cards[" + index + "]";
            Map<String, Object> card = requireObject(cards.get(index), scope);
            requireExactFields(card, CARD_FIELDS, scope);
            requireUniqueId(card, scope, ids);
            requireString(card, "title", scope);
            requireString(card, "summary", scope);
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
        return new IllegalStateException("资料数据不符合 materials 契约：" + detail);
    }
}
