package com.orderly.module.studymap;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/** 高等数学知识图谱模块：只从自身 JAR 读取只读知识数据。 */
public final class StudyMapModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_map";
    private final StudyMapCatalog catalog = new StudyMapCatalog();

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
        if (!"study.map.read".equals(queryId)) {
            throw new IllegalArgumentException("知识图谱模块未提供查询：" + queryId);
        }
        return catalog.load();
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction(invocation);
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw unsupportedAction(invocation);
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction(invocation);
    }

    private UnsupportedOperationException unsupportedAction(ModuleInvocation invocation) {
        return new UnsupportedOperationException(
                "知识图谱模块只提供只读查询，不支持动作：" + invocation.actionId()
        );
    }
}
