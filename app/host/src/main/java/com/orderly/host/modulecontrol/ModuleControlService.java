package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleEntry;
import com.orderly.host.event.ExecutionJournal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.time.Instant;

/**
 * 模块目录、启用状态、排序、设置和接口级 AI 开关的控制入口。
 *
 * <p>业务模块启停、治理写入和审计在同一协调边界内完成。停用采用执行中立即拒绝策略；
 * Action 的动态 AI 关闭还会拒绝仍被未执行 AI 计划或当前执行占用的接口。系统模块
 * 始终启用，且不能通过本服务修改启停或写入业务设置；排序接口接收包含系统模块在内的
 * 完整运行模块列表。</p>
 */
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

    /** 返回全部业务模块及当前启用状态，不包含系统模块。 */
    public List<ModuleEntry> businessModules() {
        return coordinator.read(() -> catalog.businessModules().stream()
                .map(module -> ModuleEntry.from(module, preferences.isEnabled(module.moduleId())))
                .toList());
    }

    /** 返回系统模块，并始终标记为启用。 */
    public List<ModuleEntry> systemModules() {
        return coordinator.read(() -> catalog.systemModules().stream()
                .map(module -> ModuleEntry.from(module, true))
                .toList());
    }

    /** 查询单个模块的运行态条目；不存在时抛出 404。 */
    public ModuleEntry moduleEntry(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "模块不存在：" + moduleId)
        );
        return ModuleEntry.from(module, preferences.isEnabled(moduleId));
    }

    /**
     * 启用或停用业务模块并写审计事件。
     *
     * <p>启用走普通治理写边界；停用在有执行许可时立即返回冲突。成功时先持久化
     * 治理状态，再返回与目标状态一致的新条目。</p>
     */
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

    /** 返回模块排序与设置的稳定不可变快照。 */
    public ModulePreferences preferences() {
        return coordinator.read(preferences::snapshot);
    }

    /** 返回当前接口 AI 开关与使用中 Action 键，供模组设置界面展示。 */
    public ModuleInterfaceState interfaceState() {
        return new ModuleInterfaceState(
                preferences.actionAiCallableEnabled(),
                preferences.queryAiCallableEnabled(),
                usage.inUseActionKeys(Instant.now())
        );
    }

    /**
     * 更新 Action 或 Query 的动态 AI 可调用开关。
     *
     * <p>接口必须由目标业务模块声明。Action 关闭在注册表锁内检查未执行计划和执行
     * 占用，使用中返回冲突。设置成功后持久化治理状态并追加 MODULE_CONTROL 审计事件。
     * Query 动态开关只限制 AI 查询，不阻断模块自身查询。</p>
     */
    public ModuleInterfaceState setInterfaceAiCallable(
            String moduleId,
            String kind,
            String interfaceId,
            boolean enabled
    ) {
        ModuleContract module = requireBusinessModule(moduleId);
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        if ("action".equals(normalizedKind)) {
            module.actions().stream()
                    .filter(candidate -> candidate.id().equals(interfaceId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "MODULE_ACTION_NOT_FOUND",
                            "模块未声明动作：" + moduleId + "/" + interfaceId
                    ));
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
            module.queries().stream()
                    .filter(candidate -> candidate.id().equals(interfaceId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "MODULE_QUERY_NOT_FOUND",
                            "模块未声明查询：" + moduleId + "/" + interfaceId
                    ));
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

    /** 校验并保存完整侧边栏顺序，校验、持久化与审计处于同一治理写边界。 */
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

    /** 校验并保存完整管理页顺序，顺序包含当前全部运行模块。 */
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

    /**
     * 校验并部分更新已声明业务模块设置。
     *
     * <p>未提交字段沿用当前值；未声明字段和错误类型由设置定义拒绝。保存后写审计并
     * 返回该模块的完整有效设置。</p>
     */
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

    /** 将业务模块设置恢复为清单默认值，并保存治理状态与审计事件。 */
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

    /** 要求模块是已启用业务模块；系统模块和停用模块均返回冲突。 */
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

    /**
     * 在模块治理读锁内执行启用业务模块的只读操作。
     *
     * <p>operation 不应发起写治理或执行长任务；持有读锁期间停用和刷新不能取得写锁。</p>
     */
    public <T> T withEnabledBusinessModule(
            String moduleId,
            Function<ModuleContract, T> operation
    ) {
        return coordinator.read(() -> operation.apply(requireEnabledBusinessModule(moduleId)));
    }

    /** 查询业务模块启用状态；系统模块恒为 true。 */
    public boolean isEnabled(String moduleId) {
        return preferences.isEnabled(moduleId);
    }

    /** 查询动作动态 AI 开关；缺失值按默认允许处理。 */
    public boolean isActionAiCallableEnabled(String moduleId, String actionId) {
        return preferences.isActionAiCallableEnabled(moduleId, actionId);
    }

    /** 查询 Query 动态 AI 开关；缺失值按默认允许处理。 */
    public boolean isQueryAiCallableEnabled(String moduleId, String queryId) {
        return preferences.isQueryAiCallableEnabled(moduleId, queryId);
    }

    /** 要求模块存在且为非系统业务模块；不检查启用状态。 */
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
