package com.orderly.host.modulecontrol;

import java.util.Set;

/**
 * 受保护系统模块 {@code module_system_control} 允许对外调用的固定接口集合。
 *
 * <p>该白名单同时用于契约校验和执行路由，防止系统模块通过修改 JAR 契约扩张治理权限。
 * 这些接口只操作业务模块，不允许治理控制模块自身。</p>
 */
public final class ModuleControlInterfaces {

    public static final String MODULE_ID = "module_system_control";
    public static final String ACTION_SET_MODULE_ENABLED =
            "module_system_control.module.set_enabled";
    public static final String ACTION_SET_INTERFACE_CALLABLE =
            "module_system_control.interface.set_callable";
    public static final String QUERY_LIST_MODULES =
            "module_system_control.module.list";
    public static final String QUERY_LIST_INTERFACES =
            "module_system_control.interface.list";
    public static final String QUERY_GET_SETTINGS =
            "module_system_control.setting.get";

    public static final Set<String> ACTION_IDS = Set.of(
            ACTION_SET_MODULE_ENABLED,
            ACTION_SET_INTERFACE_CALLABLE
    );
    public static final Set<String> QUERY_IDS = Set.of(
            QUERY_LIST_MODULES,
            QUERY_LIST_INTERFACES,
            QUERY_GET_SETTINGS
    );

    private ModuleControlInterfaces() {
    }

    /** 判断动作 ID 是否属于受支持的系统治理动作。 */
    public static boolean isAction(String actionId) {
        return actionId != null && ACTION_IDS.contains(actionId);
    }

    /** 判断查询 ID 是否属于受支持的系统治理查询。 */
    public static boolean isQuery(String queryId) {
        return queryId != null && QUERY_IDS.contains(queryId);
    }
}
