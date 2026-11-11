package com.orderly.kernel;

import java.util.Map;

/**
 * 业务模块 SPI。
 *
 * <p>模块实现自己的工具行为、快照内容和逆操作。授权、确认、调度顺序、
 * 失败处理和审计由宿主统一担保。</p>
 */
public interface OrderlyModule {

    /** 稳定模块 ID，必须与模块契约一致。 */
    String moduleId();

    /**
     * 在宿主就绪后初始化模块自有状态。实现必须幂等，且只能通过受控运行时写入。
     */
    default void initialize(ModuleRuntime runtime) {
        // 无需初始化状态的模块可以保留默认实现。
    }

    /**
     * 返回模块自有只读查询数据。查询 ID 和参数必须先由模块契约声明。
     */
    default Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("模块未提供查询：" + queryId);
    }

    /**
     * 在执行前收集模块自有快照。该方法只能读，不得产生副作用。
     *
     * @return 可 JSON 化的快照
     */
    Map<String, Object> prepareSnapshot(ModuleInvocation invocation, ModuleRuntime runtime);

    /** 执行正向操作。 */
    ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime);

    /** 根据快照和正向输出执行逆操作。 */
    ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    );
}
