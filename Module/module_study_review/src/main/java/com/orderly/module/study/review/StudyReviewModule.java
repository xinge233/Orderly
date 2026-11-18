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

/**
 * 复习自测模块（{@code module_study_review}）。
 *
 * <p>模块自包含只读复习题目查询和基础数据校验，只读取自身 JAR 的
 * {@code orderly-data/module_study_review.json}，不访问工作区、共享集合或其他模块数据。
 * 模块不声明写入、删除或自动阅卷动作；作答与掌握标记只在界面侧演示。宿主统一担保模块
 * 启用、查询参数、审计与 AI 可调用授权。</p>
 */
public final class StudyReviewModule implements OrderlyModule {

    public static final String MODULE_ID = "module_study_review";
    private static final String QUERY_ID = "study.review.read";
    /** 仅属于本模块 JAR 的只读复习数据。 */
    private static final String DATA_RESOURCE = "orderly-data/module_study_review.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {
    };

    /** 返回与模块契约一致的稳定模块标识。 */
    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    /**
     * 读取并校验本模块自带的复习数据。
     *
     * <p>仅支持 {@code study.review.read} 和无参数调用。查询不写工作区、集合、设置或审计状态；
     * 资源缺失、解析失败或关键数据归属错误会向宿主抛出异常，不以空数据或自动阅卷结果兜底。
     * </p>
     */
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

    /**
     * 为无动作的只读模块返回空快照。
     *
     * <p>契约没有 Action，宿主不应进入正向执行。该方法不访问运行时状态，重复调用结果一致；
     * 若发生异常调用，空快照也不代表模块授权或执行成功。</p>
     */
    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of();
    }

    /**
     * 拒绝执行任何 Action。
     *
     * <p>复习模块没有业务写入或自动阅卷能力。若宿主错误分派动作，固定抛出异常并且不改变
     * 工作区、集合、设置或界面演示状态。</p>
     */
    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        throw new UnsupportedOperationException("只读复习模块不提供执行动作");
    }

    /**
     * 返回无副作用回滚结果。
     *
     * <p>模块没有可执行写入，因此没有需要逆操作的状态；重复调用保持等价且不产生新变更。</p>
     */
    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("只读复习模块无需回滚");
    }

    /**
     * 校验数据归属、只读标记、无自动阅卷模式及题目数量一致性。
     *
     * <p>该校验只防止无效或无归属数据被展示，不宣称完成签名验证、答案判分或掌握度持久化。</p>
     */
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
