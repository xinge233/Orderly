package com.orderly.module.control;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/**
 * 模组设置系统模块（{@code module_control}）。
 *
 * <p>该模块自包含受保护的系统管理视图元数据，不声明查询、业务动作、计划规则或数据集合。
 * 模块启停、排序、接口治理和字段设置由宿主系统管理接口负责，模块不读取或改写其他模块
 * 状态。由于没有可执行动作，不存在模块自身的只读、写入或删除业务操作；宿主仍担保系统
 * 模块的唯一性、保护属性和管理接口授权。</p>
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
     * <p>系统模块契约不声明动作，宿主不应调度正向执行。该方法不读取或写入任何状态，
     * 重复调用结果相同，也不替代宿主对治理状态的快照和审计。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of();
    }

    /**
     * 对不应发生的业务动作调用返回无副作用结果。
     *
     * <p>正常路径下宿主不会向该系统模块分派 Action；若被直接调用，本方法也不修改模块
     * 状态或宿主治理数据，只返回可审计的说明。</p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        return ModuleResult.completed("模组设置不执行任何业务动作；本次调用未产生变更。");
    }

    /**
     * 返回无副作用回滚结果。
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
        return ModuleResult.completed("模组设置没有可撤销的业务动作；本次调用未产生回滚变更。");
    }
}
