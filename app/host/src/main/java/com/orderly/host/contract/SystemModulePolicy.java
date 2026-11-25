package com.orderly.host.contract;

import com.orderly.host.modulecontrol.ModuleControlInterfaces;

import java.util.Map;
import java.util.Set;

/**
 * 受保护系统模块的保留命名空间、内置准入清单和旧控制模块别名。
 *
 * <p>{@code module_system_} 只用于宿主内置模块；普通用户模块不得使用该前缀。
 * 文件名前缀只提供稳定可读性，真正特权仍由宿主内置清单和
 * {@link ModuleSystemMetadata} 的能力声明共同约束。</p>
 */
public final class SystemModulePolicy {

    public static final String SYSTEM_MODULE_PREFIX = "module_system_";
    public static final String CODEGEN_MODULE_ID = "module_system_codegen";
    public static final String LEGACY_CONTROL_MODULE_ID = "module_control";

    public static final String LEGACY_ACTION_SET_MODULE_ENABLED =
            "module_control.module.set_enabled";
    public static final String LEGACY_ACTION_SET_INTERFACE_CALLABLE =
            "module_control.interface.set_callable";
    public static final String LEGACY_QUERY_LIST_MODULES =
            "module_control.module.list";
    public static final String LEGACY_QUERY_LIST_INTERFACES =
            "module_control.interface.list";
    public static final String LEGACY_QUERY_GET_SETTINGS =
            "module_control.setting.get";

    private static final Map<String, BuiltInSystemModule> BUILT_IN_MODULES = Map.of(
            ModuleControlInterfaces.MODULE_ID,
            new BuiltInSystemModule(
                    ModuleSystemMetadata.CONTROL_ROLE,
                    Set.of(ModuleSystemMetadata.CAPABILITY_MODULE_GOVERNANCE)
            ),
            CODEGEN_MODULE_ID,
            new BuiltInSystemModule(
                    ModuleSystemMetadata.SERVICE_ROLE,
                    Set.of()
            )
    );

    private static final Map<String, String> ACTION_ALIASES = Map.of(
            LEGACY_ACTION_SET_MODULE_ENABLED,
            ModuleControlInterfaces.ACTION_SET_MODULE_ENABLED,
            LEGACY_ACTION_SET_INTERFACE_CALLABLE,
            ModuleControlInterfaces.ACTION_SET_INTERFACE_CALLABLE
    );

    private static final Map<String, String> QUERY_ALIASES = Map.of(
            LEGACY_QUERY_LIST_MODULES,
            ModuleControlInterfaces.QUERY_LIST_MODULES,
            LEGACY_QUERY_LIST_INTERFACES,
            ModuleControlInterfaces.QUERY_LIST_INTERFACES,
            LEGACY_QUERY_GET_SETTINGS,
            ModuleControlInterfaces.QUERY_GET_SETTINGS
    );

    private SystemModulePolicy() {
    }

    /** 判断模块 ID 是否占用系统保留前缀。 */
    public static boolean hasReservedPrefix(String moduleId) {
        return moduleId != null && moduleId.startsWith(SYSTEM_MODULE_PREFIX);
    }

    /** 判断模块是否登记为宿主内置受保护模块。 */
    public static boolean isBuiltIn(String moduleId) {
        return moduleId != null && BUILT_IN_MODULES.containsKey(moduleId);
    }

    /** 校验内置系统模块的声明是否与其宿主登记完全一致。 */
    public static boolean matchesBuiltIn(String moduleId, ModuleSystemMetadata metadata) {
        BuiltInSystemModule definition = BUILT_IN_MODULES.get(moduleId);
        return definition != null
                && metadata != null
                && definition.role().equals(metadata.role())
                && definition.capabilities().equals(Set.copyOf(metadata.capabilities()));
    }

    /** 把旧控制模块 ID 归一化为当前正式 ID。 */
    public static String canonicalModuleId(String moduleId) {
        return LEGACY_CONTROL_MODULE_ID.equals(moduleId)
                ? ModuleControlInterfaces.MODULE_ID
                : moduleId;
    }

    /** 把旧控制 Action ID 归一化为当前正式 ID；普通 Action 原样返回。 */
    public static String canonicalActionId(String actionId) {
        return actionId == null ? null : ACTION_ALIASES.getOrDefault(actionId, actionId);
    }

    /** 把旧控制 Query ID 归一化为当前正式 ID；普通 Query 原样返回。 */
    public static String canonicalQueryId(String queryId) {
        return queryId == null ? null : QUERY_ALIASES.getOrDefault(queryId, queryId);
    }

    /** 返回宿主内置系统模块数量，用于测试和诊断。 */
    public static int builtInModuleCount() {
        return BUILT_IN_MODULES.size();
    }

    private record BuiltInSystemModule(String role, Set<String> capabilities) {
    }
}
