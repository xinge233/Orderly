package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSettingsDefinition;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 模块启用状态、排序与设置的单文件持久化服务。 */
@Service
public class ModulePreferencesService {

    private static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "module-governance.json";

    private final ContractCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    private Map<String, Boolean> enabled = new LinkedHashMap<>();
    private List<String> sidebarOrder = new ArrayList<>();
    private List<String> managementOrder = new ArrayList<>();
    private Map<String, Map<String, Object>> moduleSettings = new LinkedHashMap<>();
    private String revision = UUID.randomUUID().toString();

    public ModulePreferencesService(
            ContractCatalog catalog,
            WorkspaceRuntime runtime,
            ObjectMapper objectMapper
    ) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.stateFile = runtime.dataRoot().resolve(FILE_NAME);
    }

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

    public synchronized boolean isEnabled(String moduleId) {
        if (catalog.isSystemModule(moduleId)) {
            return true;
        }
        return Boolean.TRUE.equals(enabled.get(moduleId));
    }

    public synchronized Map<String, Boolean> enabledSnapshot() {
        return Map.copyOf(enabled);
    }

    public synchronized ModulePreferences snapshot() {
        return new ModulePreferences(sidebarOrder, managementOrder, deepCopy(moduleSettings));
    }

    public synchronized String revision() {
        return revision;
    }

    public synchronized boolean setEnabled(String moduleId, boolean value) {
        boolean previous = isEnabled(moduleId);
        enabled.put(moduleId, value);
        persist();
        return previous;
    }

    public synchronized void updateSidebarOrder(List<String> moduleIds) {
        sidebarOrder = validateOrder(moduleIds, catalog.modules());
        persist();
    }

    public synchronized void updateManagementOrder(List<String> moduleIds) {
        managementOrder = validateOrder(moduleIds, catalog.modules());
        persist();
    }

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

    public synchronized void registerBusinessModule(ModuleContract module) {
        if (module.system() != null) {
            throw new IllegalStateException("系统模块不能被注册为业务偏好：" + module.moduleId());
        }
        Map<String, Boolean> previousEnabled = new LinkedHashMap<>(enabled);
        List<String> previousSidebarOrder = new ArrayList<>(sidebarOrder);
        List<String> previousManagementOrder = new ArrayList<>(managementOrder);
        Map<String, Map<String, Object>> previousSettings = deepCopy(moduleSettings);
        String previousRevision = revision;
        try {
            enabled.putIfAbsent(module.moduleId(), true);
            appendMissing(sidebarOrder, List.of(module));
            appendMissing(managementOrder, List.of(module));
            initializeSettings(module);
            persist();
        } catch (RuntimeException exception) {
            enabled = previousEnabled;
            sidebarOrder = previousSidebarOrder;
            managementOrder = previousManagementOrder;
            moduleSettings = previousSettings;
            revision = previousRevision;
            throw exception;
        }
    }

    private void reconcile() {
        List<ModuleContract> businessModules = catalog.businessModules();
        List<ModuleContract> allModules = catalog.modules();
        Set<String> businessIds = businessModules.stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> allIds = allModules.stream()
                .map(ModuleContract::moduleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, Boolean> reconciledEnabled = new LinkedHashMap<>();
        for (ModuleContract module : businessModules) {
            reconciledEnabled.put(
                    module.moduleId(),
                    enabled.getOrDefault(module.moduleId(), true)
            );
        }
        enabled = reconciledEnabled;
        sidebarOrder = reconcileOrder(sidebarOrder, allModules, allIds);
        managementOrder = reconcileOrder(managementOrder, allModules, allIds);

        Map<String, Map<String, Object>> reconciledSettings = new LinkedHashMap<>();
        for (ModuleContract module : businessModules) {
            ModuleSettingsDefinition definition = catalog.settingsFor(module.moduleId());
            if (definition.isEmpty()) {
                Map<String, Object> existing = moduleSettings.get(module.moduleId());
                if (existing != null && !existing.isEmpty()) {
                    reconciledSettings.put(module.moduleId(), Map.copyOf(existing));
                }
                continue;
            }
            Map<String, Object> current = moduleSettings.getOrDefault(module.moduleId(), Map.of());
            Map<String, Object> validated = definition.validateAndMerge(
                    objectMapper.valueToTree(current),
                    current,
                    objectMapper
            );
            reconciledSettings.put(module.moduleId(), validated);
        }
        moduleSettings = reconciledSettings;
    }

    private List<String> reconcileOrder(
            List<String> existing,
            List<ModuleContract> modules,
            Set<String> ids
    ) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : existing) {
            if (ids.contains(id) && seen.add(id)) {
                result.add(id);
            }
        }
        appendMissing(result, modules);
        return result;
    }

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

    private ModuleContract requireBusinessModule(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new IllegalArgumentException("模块不存在：" + moduleId)
        );
        if (module.system() != null) {
            throw new IllegalArgumentException("系统模块不允许修改业务偏好：" + moduleId);
        }
        return module;
    }

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
        sidebarOrder = parseStringArray(root.path("sidebarOrder"), "sidebarOrder");
        managementOrder = parseStringArray(root.path("managementOrder"), "managementOrder");

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

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private IllegalStateException corruptedState(Exception cause) {
        String detail = cause.getMessage();
        return new IllegalStateException(
                "模块治理状态文件损坏：" + stateFile
                        + (detail == null || detail.isBlank() ? "" : "；" + detail),
                cause
        );
    }

    private Map<String, Map<String, Object>> deepCopy(
            Map<String, Map<String, Object>> source
    ) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return copy;
    }
}
