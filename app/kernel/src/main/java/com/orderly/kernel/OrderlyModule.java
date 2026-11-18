package com.orderly.kernel;

import java.util.Map;

/**
 * 外部业务模块必须实现的宿主 SPI。
 *
 * <p>模块只实现自己的查询、Action 执行、快照采集和逆操作。授权、确认、风险判断、
 * Action 调度顺序、持久化、失败停止和审计由宿主统一担保。模块之间不得通过该 SPI
 * 直接互调；需要协作时只能由宿主顺序执行 Action，并解析前序结构化输出。</p>
 */
public interface OrderlyModule {

    /**
     * 返回稳定模块 ID。
     *
     * <p>该值必须与 JAR 内模块契约、目录和 UI 清单一致，并在宿主运行集中唯一。
     * 此方法必须无副作用，且同一执行器实例前后返回相同值。</p>
     */
    String moduleId();

    /**
     * 在宿主运行时和契约校验就绪后初始化模块自有状态。
     *
     * <p>实现必须幂等，只能通过 {@link ModuleRuntime} 读取或写入本模块状态。初始化
     * 失败应抛出明确异常并阻止该模块被接纳；默认实现表示模块无需初始化。</p>
     */
    default void initialize(ModuleRuntime runtime) {
        // 无需初始化状态的模块可以保留默认实现。
    }

    /**
     * 执行契约声明的只读 Query。
     *
     * <p>查询 ID 和参数应在契约中登记并由宿主预检。实现不得产生文件、集合、设置、
     * 审计或外部服务副作用。默认实现表示模块没有 Query，并抛出参数异常。</p>
     */
    default Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        throw new IllegalArgumentException("模块未提供查询：" + queryId);
    }

    /**
     * 在正向执行前采集逆操作所需的最小状态。
     *
     * <p>该方法必须只读且可重复调用，不得写入工作区或模块集合。返回值必须可 JSON
     * 序列化并由宿主持久化；缺失必要信息时必须失败，不能返回无法回滚的伪快照。</p>
     *
     * @return 可 JSON 化的快照
     */
    Map<String, Object> prepareSnapshot(ModuleInvocation invocation, ModuleRuntime runtime);

    /**
     * 执行当前一个已校验 Action。
     *
     * <p>实现不得自行扩张授权、调用其他模块或继续后续 Action。失败应抛出异常；
     * 宿主会记录已发生的执行事实并决定是否回滚。成功输出必须可 JSON 序列化。</p>
     */
    ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime);

    /**
     * 根据执行前快照和正向输出执行幂等逆操作。
     *
     * <p>宿主可能因失败恢复、用户回滚或重启后的记录回滚再次调用该方法。实现必须
     * 能识别目标已恢复的情况，不得覆盖原位置已有对象；删除类动作只能借助项目回收站
     * 恢复，不得调用物理删除。</p>
     */
    ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    );
}
