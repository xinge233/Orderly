package com.orderly.host.support;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/** 用于验证多个 MODULE_CONTROL 声明会在启动校验阶段被拒绝。 */
public final class TestAlternateControlModule implements OrderlyModule {

    public static final String MODULE_ID = "module_system_other_control";

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
        return ModuleResult.completed("unused");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("unused");
    }
}
