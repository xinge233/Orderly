package com.orderly.host.support;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

public final class TestControlModule implements OrderlyModule {
    @Override
    public String moduleId() {
        return "module_control";
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("No queries");
    }

    @Override
    public Map<String, Object> prepareSnapshot(ModuleInvocation invocation, ModuleRuntime runtime) {
        return Map.of();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw new IllegalArgumentException("No actions");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("rolled back");
    }
}
