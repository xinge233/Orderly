package com.orderly.module.control;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/**
 * 模组设置系统模块（{@code module_control}）。
 *
 * <p>该模块自包含受保护的系统管理视图元数据，并声明宿主治理查询与动作。实际查询、
 * 启停和接口开关由宿主专用治理服务执行；模块执行器不读取或改写其他模块状态，也不允许
 * 绕过宿主授权、快照、审计和回滚。</p>
 */
public final class ModuleControl implements OrderlyModule {

    public static final String MODULE_ID = "module_control";

    /** 返回与受保护系统模块契约一致的稳定标识。 */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 返回空快照。
     *
     * <p>系统治理动作由宿主专用执行器采集快照。该模块执行器不直接访问宿主治理状态，
     * 因此普通模块快照路径必须拒绝。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("模组设置快照由宿主治理服务提供");
    }

    /**
     * 对绕过宿主治理路由的直接调用明确失败。
     *
     * <p>正常路径下宿主不会向该系统模块分派 Action；若被直接调用，本方法也不修改模块
     * 状态或宿主治理数据，只返回可审计的说明。</p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw new IllegalArgumentException("模组设置动作只能由宿主治理服务执行");
    }

    /**
     * 对绕过宿主治理路由的直接回滚明确失败。
     *
     * <p>系统模块没有业务写入或快照内容，因此回滚不改变状态，重复调用保持等价。</p>
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("模组设置回滚只能由宿主治理服务执行");
    }
}
