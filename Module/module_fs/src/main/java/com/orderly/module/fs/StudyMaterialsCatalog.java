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
 * {@code module_fs} 自带学习资料的只读目录。
 *
 * <p>本类只读取本模块 JAR 内的 {@code orderly-data/module_fs.json}，并验证数据归属、只读标记、
 * 引用关系和固定枚举。它不访问工作区、个人目录、其他模块资源或宿主数据库，也不提供写入、
 * 删除、快照或回滚能力；无效数据直接阻止查询，不以空目录或部分结果兜底。</p>
 */
final class StudyMaterialsCatalog {

    /** 仅属于本模块 JAR 的固定资料资源。 */
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

    /** 本目录独占的 JSON 解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 只在完整校验成功后保存的只读文档缓存。 */
    private Map<String, Object> cached;

    /**
     * 加载并校验本模块学习资料。
     *
     * <p>首次成功加载后缓存根文档，后续查询复用同一数据且不会回写 JAR。资源缺失、解析失败或
     * 契约校验失败都会抛出明确异常；方法不访问 {@link ModuleRuntime} 或任意文件系统路径。</p>
     */
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

    /** 校验根元信息、来源、课程、资料以及声明数量的一致性。 */
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

    /**
     * 校验来源字段、允许类型和 ID 唯一性，返回可被资料引用的来源 ID 集合。
     */
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
     * 校验课程字段、必填文本和 ID 唯一性，返回可被资料引用的课程 ID 集合。
     */
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

    /**
     * 校验每条资料的 ID、课程/来源引用、格式、状态、摘要和卡片负载。
     *
     * <p>所有引用必须指向同一 JAR 文档内已声明的对象，避免跨模块数据被当作本模块资料展示。</p>
     */
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

    /** 校验某条资料下卡片字段、文本内容和非空 ID。 */
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

    /** 验证引用值存在，并把错误限定到当前对象和字段。 */
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

    /** 注册对象 ID；重复时拒绝整份资料文档。 */
    private void requireUniqueId(Map<String, Object> value, String scope, Set<String> ids) {
        String id = requireString(value, "id", scope);
        if (!ids.add(id)) {
            throw invalid(scope + ".id 重复：" + id);
        }
    }

    /**
     * 要求对象字段集合与契约完全一致。
     *
     * <p>错误信息分别列出缺失和多余字段，防止资源演进时未知字段被静默忽略。</p>
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

    /** 要求字段为非空白字符串，并返回原值供后续引用校验。 */
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
     * <p>转换前显式检查键类型，避免后续按字符串字段名读取时出现隐式转换或漏检。</p>
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

    /** 构造带 materials 契约前缀的数据错误，交由查询调用方报告给宿主。 */
    private IllegalStateException invalid(String detail) {
        return new IllegalStateException("资料数据不符合 materials 契约：" + detail);
    }
}
