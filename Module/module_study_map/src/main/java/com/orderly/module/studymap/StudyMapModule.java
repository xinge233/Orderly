package com.orderly.module.studymap;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

/**
 * 高等数学知识图谱模块（{@code module_study_map}）。
 *
 * <p>模块自包含知识图谱查询及其数据校验，只从本模块 JAR 的
 * {@code orderly-data/module_study_map.json} 读取只读数据，不访问工作区、其他模块集合或
 * 外部模型。模块契约不声明写入或删除动作，因此该模块的操作面仅为只读查询；宿主仍统一
 * 校验模块启用状态、查询 ID、参数与 AI 可调用策略。</p>
 */
public final class StudyMapModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_map";
    /** 本模块独占的知识图谱读取与校验入口。 */
    private final StudyMapCatalog catalog = new StudyMapCatalog();

    /** 返回与模块契约一致的稳定模块标识。 */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 读取本模块 JAR 内的知识图谱。
     *
     * <p>仅支持契约声明的 {@code study.map.read}，且不产生任何业务或审计副作用。宿主在
     * 调用前校验查询参数和模块启用状态；目录对象在首次成功加载后由本模块缓存。</p>
     */
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

    /**
     * 拒绝为未声明的动作准备快照。
     *
     * <p>该模块只有只读查询，没有需要保存的业务状态。若宿主错误地发起 Action，固定抛出
     * 不支持异常，避免制造伪快照或隐式写入。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction(invocation);
    }

    /**
     * 拒绝执行任何 Action。
     *
     * <p>模块契约的 {@code actions} 为空；该方法不写入工作区、集合或设置，错误由宿主记录。
     * </p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw unsupportedAction(invocation);
    }

    /**
     * 拒绝回滚不存在的业务动作。
     *
     * <p>只读查询没有可逆写入，因此不存在合法回滚输入；重复调用都会得到同样的拒绝结果，
     * 不会改变任何状态。</p>
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction(invocation);
    }

    /** 构造统一的“仅支持只读查询”异常，并保留调用中的动作标识。 */
    private UnsupportedOperationException unsupportedAction(ModuleInvocation invocation) {
        return new UnsupportedOperationException(
                "知识图谱模块只提供只读查询，不支持动作：" + invocation.actionId()
        );
    }
}
