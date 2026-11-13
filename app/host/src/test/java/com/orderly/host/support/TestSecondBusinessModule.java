package com.orderly.host.support;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

public final class TestSecondBusinessModule implements OrderlyModule {
    @Override
    public String moduleId() {
        return "module_business_two";
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        return Map.of("count", 1);
    }

    @Override
    public Map<String, Object> prepareSnapshot(ModuleInvocation invocation, ModuleRuntime runtime) {
        return Map.of();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return ModuleResult.completed("second business executed");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("second business rolled back");
    }
}
