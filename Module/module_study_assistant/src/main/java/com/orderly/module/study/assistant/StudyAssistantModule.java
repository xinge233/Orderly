package com.orderly.module.study.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 学习工作台模块（{@code module_study_assistant}）。
 *
 * <p>模块自包含学习工作台查询、JAR 内数据和契约元信息校验，只读取自身
 * {@code orderly-data/module_study_assistant.json}，不访问工作区、共享集合、其他模块数据或
 * 外部模型。模块不声明写入与删除动作；其操作面仅为只读查询。宿主统一负责模块启用、
 * 查询参数、审计和 AI 可调用授权，模块不自行判断调用来源。</p>
 */
public final class StudyAssistantModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_assistant";
    private static final String QUERY_ID = "study.assistant.read";
    /** 仅属于本模块 JAR 的只读工作台数据。 */
    private static final String DATA_RESOURCE = "/orderly-data/module_study_assistant.json";
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {
    };

    /** 本模块独占的 JSON 解析器，用于读取自带只读数据。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 返回与模块契约一致的稳定模块标识。 */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 执行学习工作台的唯一只读查询。
     *
     * <p>前置条件是宿主已按契约校验查询 ID 和空参数集合。方法只读取并校验本模块 JAR 数据，
     * 不访问运行时工作区，也不产生文件、集合、设置或审计副作用；返回根映射的顶层副本。</p>
     */
    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        if (!QUERY_ID.equals(queryId)) {
            throw new IllegalArgumentException("学习工作台未提供查询：" + queryId);
        }
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException("学习工作台查询不接受参数");
        }

        Map<String, Object> data = readOwnData();
        validateContract(data);
        return Map.copyOf(data);
    }

    /**
     * 拒绝为未声明的动作准备快照。
     *
     * <p>模块契约没有 Action，也不存在业务写入状态；若宿主错误分派动作，本方法只抛出明确
     * 异常，不生成伪快照或改变数据。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction();
    }

    /**
     * 拒绝执行任何 Action。
     *
     * <p>该模块只有只读查询；本方法不写入工作区、集合、设置或外部模型状态，重复调用也不会
     * 产生业务变化。</p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw unsupportedAction();
    }

    /**
     * 拒绝回滚不存在的业务动作。
     *
     * <p>没有可逆写入就没有合法回滚输入。方法保持无副作用，并由宿主记录错误和失败状态。</p>
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        throw unsupportedAction();
    }

    /**
     * 从模块 classpath 读取固定数据资源。
     *
     * <p>资源缺失或 JSON 无法解析时抛出明确异常，不退回工作区、其他模块资源或空数据。</p>
     */
    private Map<String, Object> readOwnData() {
        try (InputStream stream = StudyAssistantModule.class.getResourceAsStream(DATA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("学习工作台缺少自有数据资源：" + DATA_RESOURCE);
            }
            return objectMapper.readValue(stream, DATA_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("学习工作台无法读取自有数据", exception);
        }
    }

    /**
     * 校验数据归属和“本地规则、未连接外部模型”的诚实性标记。
     *
     * <p>只检查查询展示所依赖的关键元信息，不把校验通过表述为外部 AI 已接入或数据已签名。</p>
     */
    private void validateContract(Map<String, Object> data) {
        if (!Integer.valueOf(1).equals(data.get("schemaVersion"))
                || !"assistant".equals(data.get("kind"))
                || !MODULE_ID.equals(data.get("ownerModule"))
                || !Boolean.TRUE.equals(data.get("readOnly"))) {
            throw new IllegalStateException("学习工作台数据元信息不符合 assistant 契约");
        }

        Object payloadValue = data.get("payload");
        if (!(payloadValue instanceof Map<?, ?> payload)) {
            throw new IllegalStateException("学习工作台数据缺少 payload");
        }
        if (!"local-rules".equals(payload.get("planner"))
                || !Boolean.FALSE.equals(payload.get("externalModelConnected"))) {
            throw new IllegalStateException("学习工作台必须保持本地规则且未连接外部模型");
        }
    }

    /** 为未声明的 SPI 动作提供一致且无副作用的拒绝异常。 */
    private UnsupportedOperationException unsupportedAction() {
        return new UnsupportedOperationException("学习工作台只提供只读查询，不声明或执行动作");
    }
}
