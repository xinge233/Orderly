package com.orderly.module.control;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/**
 * 模组设置模块。
 *
 * <p>该模块只提供受保护的系统管理视图，不声明也不执行任何业务动作。模块启停、
 * 排序和字段设置均由宿主系统管理接口负责持久化。</p>
 */
public final class ModuleControl implements OrderlyModule {

    public static final String MODULE_ID = "module_control";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return ModuleResult.completed("模组设置不执行任何业务动作；本次调用未产生变更。");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("模组设置没有可撤销的业务动作；本次调用未产生回滚变更。");
    }
}
