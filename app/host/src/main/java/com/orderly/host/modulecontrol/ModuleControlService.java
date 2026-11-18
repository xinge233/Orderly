package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleEntry;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.event.ExecutionJournal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.time.Instant;

/** 模块目录、启用状态、排序和模块设置的控制入口。 */
@Service
public class ModuleControlService {

    private final ContractCatalog catalog;
    private final ModulePreferencesService preferences;
    private final ModuleControlCoordinator coordinator;
    private final InterfaceUsageRegistry usage;
    private final ExecutionJournal journal;

    public ModuleControlService(
            ContractCatalog catalog,
            ModulePreferencesService preferences,
            ModuleControlCoordinator coordinator,
            InterfaceUsageRegistry usage,
            ExecutionJournal journal
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.coordinator = coordinator;
        this.usage = usage;
        this.journal = journal;
    }

    public List<ModuleEntry> businessModules() {
        return coordinator.read(() -> catalog.businessModules().stream()
                .map(module -> ModuleEntry.from(module, preferences.isEnabled(module.moduleId())))
                .toList());
    }

    public List<ModuleEntry> systemModules() {
        return coordinator.read(() -> catalog.systemModules().stream()
                .map(module -> ModuleEntry.from(module, true))
                .toList());
    }

    public ModuleEntry moduleEntry(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "模块不存在：" + moduleId)
        );
        return ModuleEntry.from(module, preferences.isEnabled(moduleId));
    }

    public ModuleEntry setActivation(String moduleId, boolean enabled) {
        ModuleContract module = requireBusinessModule(moduleId);
        java.util.function.Supplier<ModuleEntry> operation = () -> {
            preferences.setEnabled(moduleId, enabled);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    moduleId,
                    "activation",
                    enabled ? "ENABLED" : "DISABLED",
                    enabled ? "模块已启用" : "模块已停用"
            );
            return ModuleEntry.from(module, enabled);
        };
        return enabled
                ? coordinator.mutate(operation)
                : coordinator.mutateRejectingDuringExecution(operation);
    }

    public ModulePreferences preferences() {
        return coordinator.read(preferences::snapshot);
    }

    public ModuleInterfaceState interfaceState() {
        return new ModuleInterfaceState(
                preferences.actionAiCallableEnabled(),
                preferences.queryAiCallableEnabled(),
                usage.inUseActionKeys(Instant.now())
        );
    }

    public ModuleInterfaceState setInterfaceAiCallable(
            String moduleId,
            String kind,
            String interfaceId,
            boolean enabled
    ) {
        ModuleContract module = requireBusinessModule(moduleId);
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        if ("action".equals(normalizedKind)) {
            ActionContract action = module.actions().stream()
                    .filter(candidate -> candidate.id().equals(interfaceId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "MODULE_ACTION_NOT_FOUND",
                            "模块未声明动作：" + moduleId + "/" + interfaceId
                    ));
            if (enabled && !action.aiCallable()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INTERFACE_STATICALLY_DISABLED",
                        "模块已静态禁止该动作被 AI 调用"
                );
            }
            usage.withActionLock(() -> {
                if (!enabled && usage.isActionInUse(moduleId, interfaceId, Instant.now())) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "INTERFACE_IN_USE",
                            "接口正在被未执行 AI 计划或当前执行使用"
                    );
                }
                preferences.setActionAiCallableEnabled(moduleId, interfaceId, enabled);
                journal.append(
                        "MODULE_CONTROL",
                        "MODULE_CONTROL",
                        null,
                        null,
                        moduleId,
                        interfaceId,
                        enabled ? "AI_ENABLED" : "AI_DISABLED",
                        "Action AI 可调用状态已更新"
                );
                return null;
            });
        } else if ("query".equals(normalizedKind)) {
            QueryContract query = module.queries().stream()
                    .filter(candidate -> candidate.id().equals(interfaceId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "MODULE_QUERY_NOT_FOUND",
                            "模块未声明查询：" + moduleId + "/" + interfaceId
                    ));
            if (enabled && !query.aiCallable()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INTERFACE_STATICALLY_DISABLED",
                        "模块已静态禁止该查询被 AI 调用"
                );
            }
            preferences.setQueryAiCallableEnabled(moduleId, interfaceId, enabled);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    moduleId,
                    interfaceId,
                    enabled ? "AI_ENABLED" : "AI_DISABLED",
                    "Query AI 可调用状态已更新"
            );
        } else {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INTERFACE_KIND",
                    "接口类型必须是 action 或 query"
            );
        }
        return interfaceState();
    }

    public ModulePreferences updateSidebarOrder(List<String> moduleIds) {
        return coordinator.mutate(() -> {
            preferences.updateSidebarOrder(moduleIds);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    null,
                    "sidebar-order",
                    "UPDATED",
                    "侧边栏模块顺序已保存"
            );
            return preferences.snapshot();
        });
    }

    public ModulePreferences updateManagementOrder(List<String> moduleIds) {
        return coordinator.mutate(() -> {
            preferences.updateManagementOrder(moduleIds);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    null,
                    "management-order",
                    "UPDATED",
                    "模块管理顺序已保存"
            );
            return preferences.snapshot();
        });
    }

    public Map<String, Object> updateSettings(String moduleId, JsonNode settings) {
        requireBusinessModule(moduleId);
        return coordinator.mutate(() -> {
            Map<String, Object> saved = preferences.updateSettings(moduleId, settings);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    moduleId,
                    "settings",
                    "UPDATED",
                    "模块设置已保存"
            );
            return saved;
        });
    }

    public Map<String, Object> resetSettings(String moduleId) {
        requireBusinessModule(moduleId);
        return coordinator.mutate(() -> {
            Map<String, Object> defaults = preferences.resetSettings(moduleId);
            journal.append(
                    "MODULE_CONTROL",
                    "MODULE_CONTROL",
                    null,
                    null,
                    moduleId,
                    "settings-reset",
                    "RESET",
                    "模块设置已恢复默认值"
            );
            return defaults;
        });
    }

    public ModuleContract requireEnabledBusinessModule(String moduleId) {
        ModuleContract module = requireBusinessModule(moduleId);
        if (!preferences.isEnabled(moduleId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_DISABLED",
                    "模块已停用：" + moduleId
            );
        }
        return module;
    }

    public <T> T withEnabledBusinessModule(
            String moduleId,
            Function<ModuleContract, T> operation
    ) {
        return coordinator.read(() -> operation.apply(requireEnabledBusinessModule(moduleId)));
    }

    public boolean isEnabled(String moduleId) {
        return preferences.isEnabled(moduleId);
    }

    public boolean isActionAiCallableEnabled(String moduleId, String actionId) {
        return preferences.isActionAiCallableEnabled(moduleId, actionId);
    }

    public boolean isQueryAiCallableEnabled(String moduleId, String queryId) {
        return preferences.isQueryAiCallableEnabled(moduleId, queryId);
    }

    private ModuleContract requireBusinessModule(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "模块不存在：" + moduleId)
        );
        if (module.system() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_PROTECTED",
                    "系统模块不允许修改：" + moduleId
            );
        }
        return module;
    }
}
