package com.orderly.codegen.e2e;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

public final class CodegenE2eSmokeModule implements OrderlyModule {

    private static final String MODULE_ID = "module_codegen_e2e_smoke";
    private static final String QUERY_ID = "codegen.e2e.summary";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        if (!QUERY_ID.equals(queryId)) {
            throw new IllegalArgumentException("生成链路验收未提供查询：" + queryId);
        }
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException("生成链路验收查询不接受参数");
        }
        return Map.of(
                "schemaVersion", 1,
                "moduleId", MODULE_ID,
                "readOnly", true,
                "message", "module codegen end-to-end smoke"
        );
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw unsupportedAction();
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction();
    }

    private UnsupportedOperationException unsupportedAction() {
        return new UnsupportedOperationException("生成链路验收只提供只读查询");
    }
}
