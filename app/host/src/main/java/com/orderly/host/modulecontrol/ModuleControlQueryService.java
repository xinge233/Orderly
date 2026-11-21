package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSettingsDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code module_control} 的只读系统治理查询实现。
 *
 * <p>查询结果只包含业务模块元数据和当前设置，不修改治理状态。原始设置值仍遵守 Query
 * 的本地交付规则，由 AI 网关决定是否回传模型。</p>
 */
@Service
public class ModuleControlQueryService {

    private final ContractCatalog catalog;
    private final ModulePreferencesService preferences;
    private final InterfaceUsageRegistry usage;
    private final ModuleControlCoordinator coordinator;

    public ModuleControlQueryService(
            ContractCatalog catalog,
            ModulePreferencesService preferences,
            InterfaceUsageRegistry usage,
            ModuleControlCoordinator coordinator
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.usage = usage;
        this.coordinator = coordinator;
    }

    /** 判断查询 ID 是否属于已批准的系统治理查询。 */
    public boolean supports(String moduleId, String queryId) {
        return ModuleControlInterfaces.MODULE_ID.equals(moduleId)
                && ModuleControlInterfaces.isQuery(queryId);
    }

    /** 执行一个已通过契约参数校验的系统治理查询。 */
    public Map<String, Object> query(String queryId, Map<String, String> parameters) {
        return coordinator.read(() -> switch (queryId) {
            case ModuleControlInterfaces.QUERY_LIST_MODULES -> listModules();
            case ModuleControlInterfaces.QUERY_LIST_INTERFACES -> listInterfaces(parameters);
            case ModuleControlInterfaces.QUERY_GET_SETTINGS -> getSettings(parameters);
            default -> throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "MODULE_QUERY_NOT_FOUND",
                    "系统治理查询不存在：" + queryId
            );
        });
    }

    /** 返回业务模块启用、排序和接口数量摘要。 */
    private Map<String, Object> listModules() {
        Map<String, Integer> managementIndex = new LinkedHashMap<>();
        List<String> order = preferences.snapshot().managementOrder();
        for (int index = 0; index < order.size(); index++) {
            managementIndex.put(order.get(index), index);
        }
        List<ModuleContract> modules = new ArrayList<>(catalog.businessModules());
        modules.sort(Comparator
                .comparingInt((ModuleContract module) -> managementIndex.getOrDefault(
                        module.moduleId(),
                        Integer.MAX_VALUE
                ))
                .thenComparingInt(ModuleContract::order)
                .thenComparing(ModuleContract::moduleId));
        List<Map<String, Object>> items = modules.stream()
                .map(module -> {
                    ModuleSettingsDefinition settings = catalog.settingsFor(module.moduleId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("moduleId", module.moduleId());
                    item.put("title", module.title());
                    item.put("description", module.description());
                    item.put("enabled", preferences.isEnabled(module.moduleId()));
                    item.put("managementOrder", managementIndex.getOrDefault(
                            module.moduleId(),
                            Integer.MAX_VALUE
                    ));
                    item.put("settingsCount", settings.fields().size());
                    item.put("actionCount", module.actions().size());
                    item.put("queryCount", module.queries().size());
                    return Map.copyOf(item);
                })
                .toList();
        return Map.of(
                "items", items,
                "count", items.size(),
                "revision", preferences.revision()
        );
    }

    /** 返回业务模块 Action/Query 的 AI 可调用与使用中状态。 */
    private Map<String, Object> listInterfaces(Map<String, String> parameters) {
        String moduleFilter = optionalText(parameters, "moduleId");
        String kind = optionalKind(parameters);
        List<ModuleContract> modules = moduleFilter == null
                ? catalog.businessModules()
                : List.of(requireBusinessModule(moduleFilter));
        Set<String> inUse = Set.copyOf(usage.inUseActionKeys(java.time.Instant.now()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModuleContract module : modules) {
            if (kind == null || "action".equals(kind)) {
                module.actions().forEach(action -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("moduleId", module.moduleId());
                    item.put("moduleTitle", module.title());
                    item.put("kind", "action");
                    item.put("interfaceId", action.id());
                    item.put("title", action.title());
                    item.put("description", action.description());
                    item.put("risk", action.risk().name());
                    item.put("aiCallable", preferences.isActionAiCallableEnabled(
                            module.moduleId(),
                            action.id()
                    ));
                    item.put("inUse", inUse.contains(module.moduleId() + "/" + action.id()));
                    items.add(Map.copyOf(item));
                });
            }
            if (kind == null || "query".equals(kind)) {
                module.queries().forEach(query -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("moduleId", module.moduleId());
                    item.put("moduleTitle", module.title());
                    item.put("kind", "query");
                    item.put("interfaceId", query.id());
                    item.put("title", query.title());
                    item.put("description", query.description());
                    item.put("aiCallable", preferences.isQueryAiCallableEnabled(
                            module.moduleId(),
                            query.id()
                    ));
                    item.put("inUse", false);
                    items.add(Map.copyOf(item));
                });
            }
        }
        items.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.get("moduleId")))
                .thenComparing(item -> String.valueOf(item.get("kind")))
                .thenComparing(item -> String.valueOf(item.get("interfaceId"))));
        return Map.of(
                "items", List.copyOf(items),
                "count", items.size(),
                "revision", preferences.revision()
        );
    }

    /** 返回指定业务模块的字段定义和当前有效设置。 */
    private Map<String, Object> getSettings(Map<String, String> parameters) {
        String moduleId = optionalText(parameters, "moduleId");
        if (moduleId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_PARAMETERS",
                    "查询缺少必填参数：moduleId"
            );
        }
        ModuleContract module = requireBusinessModule(moduleId);
        ModuleSettingsDefinition definition = catalog.settingsFor(moduleId);
        Map<String, Object> current = preferences.snapshot()
                .moduleSettings()
                .getOrDefault(moduleId, definition.defaults());
        return Map.of(
                "moduleId", module.moduleId(),
                "title", module.title(),
                "fields", definition.fields(),
                "values", current,
                "revision", preferences.revision()
        );
    }

    /** 只接受业务模块；系统模块不能通过治理查询暴露自身。 */
    private ModuleContract requireBusinessModule(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_NOT_FOUND",
                        "模块不存在：" + moduleId
                )
        );
        if (module.system() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_PROTECTED",
                    "系统模块不参与业务治理：" + moduleId
            );
        }
        return module;
    }

    /** 读取可选查询参数并规范化空白值。 */
    private static String optionalText(Map<String, String> parameters, String name) {
        if (parameters == null) {
            return null;
        }
        String value = parameters.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 读取并校验可选的接口类型过滤参数。 */
    private static String optionalKind(Map<String, String> parameters) {
        String value = optionalText(parameters, "kind");
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!"action".equals(normalized) && !"query".equals(normalized)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_PARAMETERS",
                    "kind 必须是 action 或 query"
            );
        }
        return normalized;
    }
}
