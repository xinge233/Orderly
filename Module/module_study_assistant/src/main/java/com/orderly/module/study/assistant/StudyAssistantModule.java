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
 * 学习工作台模块：只读取自身 JAR 内的学习数据，不访问共享状态或其他模块数据。
 */
public final class StudyAssistantModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_assistant";
    private static final String QUERY_ID = "study.assistant.read";
    private static final String DATA_RESOURCE = "/orderly-data/module_study_assistant.json";
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            throw new IllegalArgumentException("学习工作台未提供查询：" + queryId);
        }
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException("学习工作台查询不接受参数");
        }

        Map<String, Object> data = readOwnData();
        validateContract(data);
        return Map.copyOf(data);
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

    private UnsupportedOperationException unsupportedAction() {
        return new UnsupportedOperationException("学习工作台只提供只读查询，不声明或执行动作");
    }
}
