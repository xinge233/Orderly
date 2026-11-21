package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSettingsDefinition;
import com.orderly.host.contract.SystemModulePolicy;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 模块启用状态、两套排序、业务设置和接口 AI 开关的单文件持久化服务。
 *
 * <p>状态保存在应用数据目录的 {@code module-governance.json}。启动时严格解析状态，
 * 清理失效模块、校验现有设置并补齐新模块默认值；任何格式或范围错误都转为包含文件
 * 路径的损坏错误，不静默回退为空状态。每次变更都重新生成 revision。</p>
 */
@Service
public class ModulePreferencesService {

    private static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "module-governance.json";
    private static final Pattern MODULE_SOURCE = Pattern.compile("module_[a-z0-9_]+");

    private final ContractCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Path stateFile;
    private final Path moduleSourceDirectory;

    private Map<String, Boolean> enabled = new LinkedHashMap<>();
    private List<String> sidebarOrder = new ArrayList<>();
    private List<String> managementOrder = new ArrayList<>();
    private Map<String, Map<String, Object>> moduleSettings = new LinkedHashMap<>();
    private Map<String, Map<String, Boolean>> actionAiCallableEnabled = new LinkedHashMap<>();
    private Map<String, Map<String, Boolean>> queryAiCallableEnabled = new LinkedHashMap<>();
    private String revision = UUID.randomUUID().toString();

    public ModulePreferencesService(
            ContractCatalog catalog,
            WorkspaceRuntime runtime,
            ObjectMapper objectMapper,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory
    ) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.stateFile = runtime.dataRoot().resolve(FILE_NAME);
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
    }

    /**
     * 加载现有治理状态并调和当前运行模块。
     *
     * <p>状态文件不存在时从当前模块目录生成默认值；不存在、读取失败或违反 schema 时
     * 阻止启动。协调完成后立即持久化规范化结果。</p>
     */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(stateFile.getParent());
            if (Files.exists(stateFile)) {
                readState();
            }
            reconcile();
        } catch (RuntimeException | IOException exception) {
            throw corruptedState(exception);
        }
        persist();
    }

    /** 系统模块恒启用；未知或未登记的业务模块按停用处理。 */
    public synchronized boolean isEnabled(String moduleId) {
        if (catalog.isSystemModule(moduleId)) {
            return true;
        }
        return Boolean.TRUE.equals(enabled.get(moduleId));
    }

    /** 返回业务模块启用映射的不可变快照。 */
    public synchronized Map<String, Boolean> enabledSnapshot() {
        return Map.copyOf(enabled);
    }

    /** 返回两套顺序和设置的不可变视图。 */
    public synchronized ModulePreferences snapshot() {
        return new ModulePreferences(
                loadedOrder(sidebarOrder),
                loadedOrder(managementOrder),
                loadedSettings()
        );
    }

    /** 返回 Action 动态 AI 开关的深复制快照。 */
    public synchronized Map<String, Map<String, Boolean>> actionAiCallableEnabled() {
        return copyFlags(actionAiCallableEnabled);
    }

    /** 返回 Query 动态 AI 开关的深复制快照。 */
    public synchronized Map<String, Map<String, Boolean>> queryAiCallableEnabled() {
        return copyFlags(queryAiCallableEnabled);
    }

    /** 查询 Action 动态开关；未登记值默认允许。 */
    public synchronized boolean isActionAiCallableEnabled(String moduleId, String actionId) {
        return actionAiCallableEnabled
                .getOrDefault(moduleId, Map.of())
                .getOrDefault(actionId, true);
    }

    /** 查询 Query 动态开关；未登记值默认允许。 */
    public synchronized boolean isQueryAiCallableEnabled(String moduleId, String queryId) {
        return queryAiCallableEnabled
                .getOrDefault(moduleId, Map.of())
                .getOrDefault(queryId, true);
    }

    /** 更新 Action 动态 AI 开关并立即写入治理状态。 */
    public synchronized void setActionAiCallableEnabled(
            String moduleId,
            String actionId,
            boolean enabled
    ) {
        actionAiCallableEnabled.computeIfAbsent(moduleId, ignored -> new LinkedHashMap<>())
                .put(actionId, enabled);
        persist();
    }

    /** 更新 Query 动态 AI 开关并立即写入治理状态。 */
    public synchronized void setQueryAiCallableEnabled(
            String moduleId,
            String queryId,
            boolean enabled
    ) {
        queryAiCallableEnabled.computeIfAbsent(moduleId, ignored -> new LinkedHashMap<>())
                .put(queryId, enabled);
        persist();
    }

    /** 返回当前治理状态的代次标识；每次持久化尝试都会先生成新值。 */
    public synchronized String revision() {
        return revision;
    }

    /** 设置业务模块启用值并持久化，返回变更前的值。 */
    public synchronized boolean setEnabled(String moduleId, boolean value) {
        boolean previous = isEnabled(moduleId);
        enabled.put(moduleId, value);
        persist();
        return previous;
    }

    /** 校验并保存完整的侧边栏顺序。 */
    public synchronized void updateSidebarOrder(List<String> moduleIds) {
        sidebarOrder = mergePendingOrder(
                validateOrder(moduleIds, catalog.modules()),
                sidebarOrder
        );
        persist();
    }

    /** 校验并保存完整的管理页顺序。 */
    public synchronized void updateManagementOrder(List<String> moduleIds) {
        managementOrder = mergePendingOrder(
                validateOrder(moduleIds, catalog.modules()),
                managementOrder
        );
        persist();
    }

    /**
     * 按模块清单定义校验部分设置并与当前值合并。
     *
     * <p>未声明字段、类型错误和范围错误不会落盘；成功时保存完整有效设置并返回副本。</p>
     */
    public synchronized Map<String, Object> updateSettings(
            String moduleId,
            JsonNode supplied
    ) {
        ModuleContract module = requireBusinessModule(moduleId);
        ModuleSettingsDefinition definition = catalog.settingsFor(moduleId);
        if (definition.isEmpty()) {
            throw new IllegalArgumentException("模块未声明可配置设置：" + moduleId);
        }
        Map<String, Object> current = moduleSettings.getOrDefault(moduleId, Map.of());
        Map<String, Object> updated = definition.validateAndMerge(supplied, current, objectMapper);
        moduleSettings.put(moduleId, updated);
        persist();
        return updated;
    }

    /** 将业务模块设置替换为清单默认值并持久化。 */
    public synchronized Map<String, Object> resetSettings(String moduleId) {
        requireBusinessModule(moduleId);
        ModuleSettingsDefinition definition = catalog.settingsFor(moduleId);
        if (definition.isEmpty()) {
            throw new IllegalArgumentException("模块未声明可配置设置：" + moduleId);
        }
        Map<String, Object> defaults = definition.defaults();
        moduleSettings.put(moduleId, defaults);
        persist();
        return defaults;
    }

    /**
     * 注册新业务模块并持久化其默认启用、排序、设置和接口开关。
     *
     * <p>持久化失败时恢复注册前的全部内存字段和 revision，避免内存状态领先磁盘。</p>
     */
    public synchronized void registerBusinessModule(ModuleContract module) {
        if (module.system() != null) {
            throw new IllegalStateException("系统模块不能被注册为业务偏好：" + module.moduleId());
        }
        Map<String, Boolean> previousEnabled = new LinkedHashMap<>(enabled);
        List<String> previousSidebarOrder = new ArrayList<>(sidebarOrder);
        List<String> previousManagementOrder = new ArrayList<>(managementOrder);
        Map<String, Map<String, Object>> previousSettings = deepCopy(moduleSettings);
        Map<String, Map<String, Boolean>> previousActions = copyFlags(actionAiCallableEnabled);
        Map<String, Map<String, Boolean>> previousQueries = copyFlags(queryAiCallableEnabled);
        String previousRevision = revision;
        try {
            enabled.putIfAbsent(module.moduleId(), true);
            appendMissing(sidebarOrder, List.of(module));
            appendMissing(managementOrder, List.of(module));
            initializeSettings(module);
            initializeInterfaceFlags(module);
            persist();
        } catch (RuntimeException exception) {
            enabled = previousEnabled;
            sidebarOrder = previousSidebarOrder;
            managementOrder = previousManagementOrder;
            moduleSettings = previousSettings;
            actionAiCallableEnabled = previousActions;
            queryAiCallableEnabled = previousQueries;
            revision = previousRevision;
            throw exception;
        }
    }

    /**
     * 使持久化状态与当前模块目录一致。
     *
     * <p>移除失效模块和接口 ID，按契约顺序补齐缺失模块，校验设置并保留已知动态开关；
     * 新接口默认允许 AI，新业务模块插入到首个系统模块之前。</p>
     */
    private void reconcile() {
        List<ModuleContract> businessModules = catalog.businessModules();
        List<ModuleContract> allModules = catalog.modules();
        Set<String> businessIds = new LinkedHashSet<>(
                businessModules.stream().map(ModuleContract::moduleId).toList()
        );
        List<String> pendingBusinessIds = pendingBusinessIds();
        businessIds.addAll(pendingBusinessIds);
        Set<String> allIds = allModules.stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        allIds.addAll(pendingBusinessIds);

        Map<String, Boolean> reconciledEnabled = new LinkedHashMap<>();
        for (String moduleId : businessIds) {
            reconciledEnabled.put(moduleId, enabled.getOrDefault(moduleId, true));
        }
        enabled = reconciledEnabled;
        sidebarOrder = reconcileOrder(sidebarOrder, allModules, allIds, pendingBusinessIds);
        managementOrder = reconcileOrder(managementOrder, allModules, allIds, pendingBusinessIds);

        Map<String, Map<String, Object>> reconciledSettings = deepCopy(moduleSettings);
        reconciledSettings.keySet().removeIf(moduleId -> !businessIds.contains(moduleId));
        for (ModuleContract module : businessModules) {
            ModuleSettingsDefinition definition = catalog.settingsFor(module.moduleId());
            if (definition.isEmpty()) {
                Map<String, Object> existing = reconciledSettings.get(module.moduleId());
                if (existing != null && !existing.isEmpty()) {
                    reconciledSettings.put(module.moduleId(), Map.copyOf(existing));
                }
                continue;
            }
            Map<String, Object> current = reconciledSettings.getOrDefault(module.moduleId(), Map.of());
            Map<String, Object> validated = definition.validateAndMerge(
                    objectMapper.valueToTree(current),
                    current,
                    objectMapper
            );
            reconciledSettings.put(module.moduleId(), validated);
        }
        moduleSettings = reconciledSettings;

        Map<String, Map<String, Boolean>> reconciledActions = copyFlags(actionAiCallableEnabled);
        Map<String, Map<String, Boolean>> reconciledQueries = copyFlags(queryAiCallableEnabled);
        reconciledActions.keySet().removeIf(moduleId -> !businessIds.contains(moduleId));
        reconciledQueries.keySet().removeIf(moduleId -> !businessIds.contains(moduleId));
        for (ModuleContract module : businessModules) {
            Map<String, Boolean> actionValues = new LinkedHashMap<>();
            for (var action : module.actions()) {
                actionValues.put(
                        action.id(),
                        actionAiCallableEnabled
                                .getOrDefault(module.moduleId(), Map.of())
                                .getOrDefault(action.id(), true)
                );
            }
            if (!actionValues.isEmpty()) {
                reconciledActions.put(module.moduleId(), new LinkedHashMap<>(actionValues));
            }

            Map<String, Boolean> queryValues = new LinkedHashMap<>();
            for (var query : module.queries()) {
                queryValues.put(
                        query.id(),
                        queryAiCallableEnabled
                                .getOrDefault(module.moduleId(), Map.of())
                                .getOrDefault(query.id(), true)
                );
            }
            if (!queryValues.isEmpty()) {
                reconciledQueries.put(module.moduleId(), new LinkedHashMap<>(queryValues));
            }
        }
        actionAiCallableEnabled = reconciledActions;
        queryAiCallableEnabled = reconciledQueries;
    }

    /** 保留现有顺序中仍有效且不重复的 ID，再按契约顺序补齐缺失模块。 */
    private List<String> reconcileOrder(
            List<String> existing,
            List<ModuleContract> modules,
            Set<String> ids,
            List<String> pendingBusinessIds
    ) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : existing) {
            if (ids.contains(id) && seen.add(id)) {
                result.add(id);
            }
        }
        appendPendingIds(result, pendingBusinessIds, modules);
        appendMissing(result, modules);
        return result;
    }

    private void appendPendingIds(
            List<String> order,
            List<String> pendingBusinessIds,
            List<ModuleContract> modules
    ) {
        Set<String> present = new LinkedHashSet<>(order);
        Set<String> systemIds = modules.stream()
                .filter(module -> module.system() != null)
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String moduleId : pendingBusinessIds) {
            if (!present.add(moduleId)) {
                continue;
            }
            int insertionIndex = order.size();
            for (int index = 0; index < order.size(); index++) {
                if (systemIds.contains(order.get(index))) {
                    insertionIndex = index;
                    break;
                }
            }
            order.add(insertionIndex, moduleId);
        }
    }

    private List<String> pendingBusinessIds() {
        if (!Files.isDirectory(moduleSourceDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(moduleSourceDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(moduleSourceDirectory)) {
            return stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> MODULE_SOURCE.matcher(name).matches())
                    .filter(name -> !SystemModulePolicy.hasReservedPrefix(name))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描待构建模块源码失败：" + moduleSourceDirectory, exception);
        }
    }

    /**
     * 把缺失模块追加到顺序中。
     *
     * <p>业务模块插到首个系统模块之前，系统模块按当前遍历位置追加。</p>
     */
    private void appendMissing(List<String> order, List<ModuleContract> modules) {
        Set<String> present = new LinkedHashSet<>(order);
        for (ModuleContract module : modules) {
            if (present.add(module.moduleId())) {
                int insertionIndex = order.size();
                if (module.system() == null) {
                    for (int index = 0; index < order.size(); index++) {
                        if (catalog.isSystemModule(order.get(index))) {
                            insertionIndex = index;
                            break;
                        }
                    }
                }
                order.add(insertionIndex, module.moduleId());
            }
        }
    }

    /** 要求排序无重复且完整包含当前全部运行模块，并返回不可变副本。 */
    private List<String> validateOrder(List<String> supplied, List<ModuleContract> modules) {
        if (supplied == null) {
            throw new IllegalArgumentException("模块排序必须是字符串数组");
        }
        List<String> expectedIds = modules.stream()
                .map(ModuleContract::moduleId)
                .toList();
        Set<String> expected = new LinkedHashSet<>(expectedIds);
        if (supplied.size() != expected.size()
                || new LinkedHashSet<>(supplied).size() != supplied.size()
                || !expected.equals(new LinkedHashSet<>(supplied))) {
            throw new IllegalArgumentException("模块排序必须无重复且完整包含当前运行模块");
        }
        return List.copyOf(supplied);
    }

    /**
     * 对外排序只暴露当前已加载模块。
     *
     * <p>内部顺序仍可保留待构建源码的槽位，等模块追加成功后自然恢复；这样治理 API
     * 不会返回 `/api/modules` 中不存在的模块 ID。</p>
     */
    private List<String> loadedOrder(List<String> order) {
        Set<String> loadedIds = catalog.modules().stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return order.stream().filter(loadedIds::contains).toList();
    }

    /** 对外设置快照同样只包含已加载业务模块，待构建设置保留在内部状态。 */
    private Map<String, Map<String, Object>> loadedSettings() {
        Set<String> loadedIds = catalog.modules().stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Map<String, Object>> snapshot = deepCopy(moduleSettings);
        snapshot.keySet().removeIf(moduleId -> !loadedIds.contains(moduleId));
        return snapshot;
    }

    /**
     * 将用户提交的已加载顺序与内部待构建槽位合并。
     *
     * <p>待构建条目统一保留在首个系统模块之前；用户对已加载模块的排序不变。</p>
     */
    private List<String> mergePendingOrder(List<String> supplied, List<String> previous) {
        Set<String> loadedIds = catalog.modules().stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> merged = new ArrayList<>(supplied);
        List<String> pending = previous.stream()
                .filter(moduleId -> !loadedIds.contains(moduleId))
                .filter(moduleId -> MODULE_SOURCE.matcher(moduleId).matches())
                .filter(moduleId -> !SystemModulePolicy.hasReservedPrefix(moduleId))
                .toList();
        if (pending.isEmpty()) {
            return List.copyOf(merged);
        }
        int insertionIndex = merged.size();
        for (int index = 0; index < merged.size(); index++) {
            if (catalog.isSystemModule(merged.get(index))) {
                insertionIndex = index;
                break;
            }
        }
        for (String moduleId : pending) {
            if (!merged.contains(moduleId)) {
                merged.add(insertionIndex++, moduleId);
            }
        }
        return List.copyOf(merged);
    }

    /** 要求目标模块为非系统业务模块；不检查模块启用状态。 */
    private ModuleContract requireBusinessModule(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new IllegalArgumentException("模块不存在：" + moduleId)
        );
        if (module.system() != null) {
            throw new IllegalArgumentException("系统模块不允许修改业务偏好：" + moduleId);
        }
        return module;
    }

    /** 校验并补齐单个业务模块设置；模块没有设置定义时移除旧值。 */
    private void initializeSettings(ModuleContract module) {
        ModuleSettingsDefinition definition = catalog.settingsFor(module.moduleId());
        if (definition.isEmpty()) {
            moduleSettings.remove(module.moduleId());
            return;
        }
        Map<String, Object> current = moduleSettings.getOrDefault(module.moduleId(), Map.of());
        moduleSettings.put(
                module.moduleId(),
                definition.validateAndMerge(
                        objectMapper.valueToTree(current),
                        current,
                        objectMapper
                )
        );
    }

    /** 为模块新声明的 Action/Query 设置默认 AI 开关，保留已有用户选择。 */
    private void initializeInterfaceFlags(ModuleContract module) {
        Map<String, Boolean> actions = actionAiCallableEnabled.computeIfAbsent(
                module.moduleId(),
                ignored -> new LinkedHashMap<>()
        );
        module.actions().forEach(action -> actions.putIfAbsent(action.id(), true));
        if (actions.isEmpty()) {
            actionAiCallableEnabled.remove(module.moduleId());
        }

        Map<String, Boolean> queries = queryAiCallableEnabled.computeIfAbsent(
                module.moduleId(),
                ignored -> new LinkedHashMap<>()
        );
        module.queries().forEach(query -> queries.putIfAbsent(query.id(), true));
        if (queries.isEmpty()) {
            queryAiCallableEnabled.remove(module.moduleId());
        }
    }

    /**
     * 严格解析治理 JSON。
     *
     * <p>version 必须匹配；enabled、两套顺序、设置和接口开关均校验节点类型，revision
     * 若存在必须为非空文本。该阶段只更新内存字段，成功后由 initialize 统一落盘。</p>
     */
    private void readState() throws IOException {
        JsonNode root = objectMapper.readTree(stateFile.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("根节点必须是对象");
        }
        if (root.path("version").asInt(-1) != SCHEMA_VERSION) {
            throw new IllegalStateException("不支持的 version");
        }
        JsonNode rawEnabled = root.path("enabled");
        if (!rawEnabled.isObject()) {
            throw new IllegalStateException("enabled 必须是对象");
        }
        Map<String, Boolean> parsedEnabled = new LinkedHashMap<>();
        for (var iterator = rawEnabled.fields(); iterator.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (!entry.getValue().isBoolean()) {
                throw new IllegalStateException("enabled." + entry.getKey() + " 必须是布尔值");
            }
            parsedEnabled.put(entry.getKey(), entry.getValue().asBoolean());
        }
        sidebarOrder = canonicalizeLegacyIds(
                parseStringArray(root.path("sidebarOrder"), "sidebarOrder")
        );
        managementOrder = canonicalizeLegacyIds(
                parseStringArray(root.path("managementOrder"), "managementOrder")
        );
        actionAiCallableEnabled = parseFlags(
                root.path("actionAiCallableEnabled"),
                "actionAiCallableEnabled"
        );
        queryAiCallableEnabled = parseFlags(
                root.path("queryAiCallableEnabled"),
                "queryAiCallableEnabled"
        );

        JsonNode rawSettings = root.path("moduleSettings");
        if (!rawSettings.isObject()) {
            throw new IllegalStateException("moduleSettings 必须是对象");
        }
        Map<String, Map<String, Object>> parsedSettings = new LinkedHashMap<>();
        for (var iterator = rawSettings.fields(); iterator.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (!entry.getValue().isObject()) {
                throw new IllegalStateException("moduleSettings." + entry.getKey() + " 必须是对象");
            }
            parsedSettings.put(
                    entry.getKey(),
                    objectMapper.convertValue(
                            entry.getValue(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            }
                    )
            );
        }
        enabled = parsedEnabled;
        moduleSettings = parsedSettings;
        JsonNode rawRevision = root.get("revision");
        if (rawRevision != null) {
            if (!rawRevision.isTextual() || rawRevision.asText().isBlank()) {
                throw new IllegalStateException("revision 必须是非空文本");
            }
            revision = rawRevision.asText();
        }
    }

    /** 将旧控制模块 ID 映射到正式系统模块 ID，并保持首次出现顺序。 */
    private List<String> canonicalizeLegacyIds(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String canonical = SystemModulePolicy.canonicalModuleId(value);
            if (canonical != null && !canonical.isBlank()) {
                seen.add(canonical);
            }
        }
        return new ArrayList<>(seen);
    }

    /** 解析非空字符串数组，保留原顺序。 */
    private List<String> parseStringArray(JsonNode raw, String field) {
        if (!raw.isArray()) {
            throw new IllegalStateException(field + " 必须是字符串数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : raw) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalStateException(field + " 只能包含非空字符串");
            }
            values.add(value.asText());
        }
        return values;
    }

    /** 解析两级布尔开关对象；缺失或 null 节点按空映射兼容旧状态。 */
    private Map<String, Map<String, Boolean>> parseFlags(JsonNode raw, String field) {
        if (raw.isMissingNode() || raw.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!raw.isObject()) {
            throw new IllegalStateException(field + " 必须是对象");
        }
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        for (var moduleIterator = raw.fields(); moduleIterator.hasNext(); ) {
            Map.Entry<String, JsonNode> moduleEntry = moduleIterator.next();
            if (!moduleEntry.getValue().isObject()) {
                throw new IllegalStateException(
                        field + "." + moduleEntry.getKey() + " 必须是对象"
                );
            }
            Map<String, Boolean> values = new LinkedHashMap<>();
            for (var interfaceIterator = moduleEntry.getValue().fields();
                 interfaceIterator.hasNext(); ) {
                Map.Entry<String, JsonNode> interfaceEntry = interfaceIterator.next();
                if (!interfaceEntry.getValue().isBoolean()) {
                    throw new IllegalStateException(
                            field + "." + moduleEntry.getKey() + "." + interfaceEntry.getKey()
                                    + " 必须是布尔值"
                    );
                }
                values.put(interfaceEntry.getKey(), interfaceEntry.getValue().asBoolean());
            }
            result.put(moduleEntry.getKey(), values);
        }
        return result;
    }

    /**
     * 生成新 revision 并原子式替换治理状态文件。
     *
     * <p>先写同目录临时文件，再优先 ATOMIC_MOVE + REPLACE_EXISTING；平台不支持时
     * 回退普通替换。失败抛出包含状态文件路径的异常。</p>
     */
    private void persist() {
        revision = UUID.randomUUID().toString();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", SCHEMA_VERSION);
        root.put("revision", revision);
        ObjectNode enabledNode = root.putObject("enabled");
        enabled.forEach(enabledNode::put);
        root.set("sidebarOrder", stringArray(sidebarOrder));
        root.set("managementOrder", stringArray(managementOrder));
        ObjectNode settingsNode = root.putObject("moduleSettings");
        moduleSettings.forEach((moduleId, values) ->
                settingsNode.set(moduleId, objectMapper.valueToTree(values))
        );
        root.set("actionAiCallableEnabled", flagObject(actionAiCallableEnabled));
        root.set("queryAiCallableEnabled", flagObject(queryAiCallableEnabled));

        Path temporary = stateFile.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(
                        temporary,
                        stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入模块治理状态失败：" + stateFile, exception);
        }
    }

    /** 将字符串列表转换为 Jackson 数组节点。 */
    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    /** 将模块到接口的两级布尔映射转换为 JSON 对象。 */
    private ObjectNode flagObject(Map<String, Map<String, Boolean>> source) {
        ObjectNode result = objectMapper.createObjectNode();
        source.forEach((moduleId, values) -> {
            ObjectNode moduleNode = result.putObject(moduleId);
            values.forEach(moduleNode::put);
        });
        return result;
    }

    /** 包装启动期状态错误，明确标注文件路径和可用的根因摘要。 */
    private IllegalStateException corruptedState(Exception cause) {
        String detail = cause.getMessage();
        return new IllegalStateException(
                "模块治理状态文件损坏：" + stateFile
                        + (detail == null || detail.isBlank() ? "" : "；" + detail),
                cause
        );
    }

    /** 深复制设置外层和内层对象，避免快照与运行态共享可变映射。 */
    private Map<String, Map<String, Object>> deepCopy(
            Map<String, Map<String, Object>> source
    ) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return copy;
    }

    /** 深复制两级布尔开关映射。 */
    private Map<String, Map<String, Boolean>> copyFlags(
            Map<String, Map<String, Boolean>> source
    ) {
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new LinkedHashMap<>(value)));
        return copy;
    }
}
