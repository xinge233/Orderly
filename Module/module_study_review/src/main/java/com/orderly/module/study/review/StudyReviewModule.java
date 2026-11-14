package com.orderly.module.study.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** 只读复习模块：从自身 JAR 读取复习题目，不提供业务写操作。 */
public final class StudyReviewModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_review";
    private static final String QUERY_ID = "study.review.read";
    private static final String DATA_RESOURCE = "orderly-data/module_study_review.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {
    };

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
            throw new IllegalArgumentException("复习模块未提供查询：" + queryId);
        }
        if (parameters != null && !parameters.isEmpty()) {
            throw new IllegalArgumentException("复习查询不接受参数");
        }

        try (InputStream stream = StudyReviewModule.class.getClassLoader()
                .getResourceAsStream(DATA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("复习模块缺少自身数据资源：" + DATA_RESOURCE);
            }
            Map<String, Object> data = OBJECT_MAPPER.readValue(stream, DATA_TYPE);
            assertReadOnlyReview(data);
            return data;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取复习模块自身数据：" + DATA_RESOURCE, error);
        }
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw new UnsupportedOperationException("只读复习模块不提供执行动作");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("只读复习模块无需回滚");
    }

    private void assertReadOnlyReview(Map<String, Object> data) {
        if (!MODULE_ID.equals(data.get("ownerModule"))
                || !"review".equals(data.get("kind"))
                || !Boolean.TRUE.equals(data.get("readOnly"))) {
            throw new IllegalStateException("复习模块数据必须属于本模块的只读 review 数据");
        }
        if (!(data.get("payload") instanceof Map<?, ?> payload)
                || !"none".equals(payload.get("gradingMode"))
                || !(payload.get("questions") instanceof List<?> questions)
                || !(data.get("count") instanceof Number count)
                || count.intValue() != questions.size()) {
            throw new IllegalStateException("复习模块数据不符合 review 负载约定");
        }
    }
}
