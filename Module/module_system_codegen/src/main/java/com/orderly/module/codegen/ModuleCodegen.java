package com.orderly.module.codegen;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/**
 * 模块与动效生成器系统模块。
 *
 * <p>该执行器保持空契约，避免把生成器自身暴露成通用 AI Action。模块 UI 只调用
 * 宿主专用生成接口；提示词和 Python 提案逻辑位于本模块的 {@code ai/} 目录，
 * 候选构建、镜像预览、版本账本和步骤回滚由宿主统一担保。</p>
 */
public final class ModuleCodegen implements OrderlyModule {

    public static final String MODULE_ID = "module_system_codegen";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("生成器尚未声明可执行动作");
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw new IllegalArgumentException("生成器尚未声明可执行动作");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("生成器尚未声明可回滚动作");
    }
}
