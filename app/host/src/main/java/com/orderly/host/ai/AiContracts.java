package com.orderly.host.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.orderly.host.contract.QueryDisplayContract;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.kernel.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 定义 Java 宿主、HTTP 接口与 Python AI 服务之间传递的稳定数据结构。
 *
 * <p>这些 record 只承载设置、回合、工具卡、查询结果和独立计划数据，不保存
 * 文件权限或执行能力。API Key 仅存在于入站 {@link SettingsRequest} 中，
 * {@link SettingsView} 等响应类型不会回传凭据。集合字段在紧凑构造器中复制，
 * 避免调用方在请求处理期间修改已接收的数据。</p>
 */
public final class AiContracts {

    private AiContracts() {
    }

    /** Python AI 服务当前支持的模型提供方模式。 */
    public enum Mode {
        DEEPSEEK,
        CUSTOM
    }

    /**
     * 更新 AI 设置的入站请求。
     *
     * <p>{@code apiKey} 是一次性写入字段，只转发给 Python 进程内存；该 record
     * 的 {@link #toString()} 输出会将已有的 Key 替换为
     * {@code [redacted]}，避免无意写日志。</p>
     */
    public record SettingsRequest(
            Mode mode,
            String apiKey,
            String baseUrl,
            String model,
            Boolean allowDeleteDirect
    ) {
        @Override
        public String toString() {
            return "SettingsRequest["
                    + "mode=" + mode
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? null : "[redacted]")
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", allowDeleteDirect=" + allowDeleteDirect
                    + "]";
        }
    }

    /** 可安全返回前端的设置视图；不包含 API Key。 */
    public record SettingsView(
            Mode mode,
            String baseUrl,
            String model,
            boolean configured,
            boolean allowDeleteDirect,
            boolean allowLocalProvider,
            int settingsVersion
    ) {
    }

    /** Python 服务对当前内存模型配置的连通性测试结果。 */
    public record ConnectionTest(boolean ok, String message) {
    }

    /** 前端发起自然语言回合的请求。 */
    public record TurnRequest(
            String goal,
            String conversationId,
            String clientRequestId
    ) {
    }

    /**
     * 宿主返回给前端的结构化回合结果。
     *
     * <p>不同 {@code kind} 只填充对应字段；可空的执行记录仍由 Java 宿主生成，
     * Python 不能通过该 DTO 声明动作已经执行。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnResponse(
            String kind,
            String requestId,
            String conversationId,
            String message,
            RiskLevel highestRisk,
            List<Action> actions,
            List<QueryCall> queries,
            List<QueryResult> queryResults,
            QueryView view,
            Plan plan,
            Question question,
            ExecutionRecord execution
    ) {
        public TurnResponse {
            actions = copy(actions);
            queries = copy(queries);
            queryResults = copy(queryResults);
        }

        /** 兼容尚未携带展示整理结果的既有调用。 */
        public TurnResponse(
                String kind,
                String requestId,
                String conversationId,
                String message,
                RiskLevel highestRisk,
                List<Action> actions,
                List<QueryCall> queries,
                List<QueryResult> queryResults,
                Plan plan,
                Question question,
                ExecutionRecord execution
        ) {
            this(
                    kind,
                    requestId,
                    conversationId,
                    message,
                    highestRisk,
                    actions,
                    queries,
                    queryResults,
                    null,
                    plan,
                    question,
                    execution
            );
        }
    }

    /** 总体 AI 提议由宿主执行的一项模块动作。 */
    public record Action(
            String moduleId,
            String actionId,
            Map<String, Object> arguments
    ) {
        public Action {
            arguments = copyMap(arguments);
        }
    }

    /** 总体 AI 提议由宿主校验并执行的一条只读模块查询。 */
    public record QueryCall(
            String moduleId,
            String queryId,
            Map<String, Object> arguments
    ) {
        public QueryCall {
            arguments = copyMap(arguments);
        }
    }

    /** 单条 Query 的受控执行状态。 */
    public enum QueryStatus {
        OK,
        FAILED,
        TRUNCATED
    }

    /**
     * Query 的本地执行结果。
     *
     * <p>只有 {@link QueryStatus#OK} 可以携带对象或数组 payload；失败和截断结果
     * 必须省略 payload，避免前端或续聊请求误用不完整数据。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryResult(
            String moduleId,
            String queryId,
            QueryStatus status,
            Object payload,
            String message
    ) {
        public QueryResult {
            if (status == QueryStatus.OK) {
                if (!(payload instanceof Map<?, ?>) && !(payload instanceof List<?>)) {
                    throw new IllegalArgumentException("OK 查询结果 payload 必须是对象或数组");
                }
            } else if (payload != null) {
                throw new IllegalArgumentException("非 OK 查询结果不能包含 payload");
            }
        }
    }

    /** AI 在信息不足时向用户提出的澄清问题和可选回答。 */
    public record Question(
            String prompt,
            List<String> options
    ) {
        public Question {
            options = copy(options);
        }
    }

    /** Java 宿主管理的独立 AI 计划预览及短时确认状态。 */
    public record Plan(
            String planId,
            String goal,
            String status,
            RiskLevel highestRisk,
            List<PlanStep> steps,
            Instant createdAt,
            Instant expiresAt
    ) {
        public Plan {
            steps = copy(steps);
        }
    }

    /** 独立 AI 计划中的单个动作预览。 */
    public record PlanStep(
            String id,
            String moduleId,
            String actionId,
            String title,
            String description,
            RiskLevel risk,
            Map<String, Object> arguments
    ) {
        public PlanStep {
            arguments = copyMap(arguments);
        }
    }

    /** 提供给 Python 模型的单个参数声明。 */
    public record ToolParameter(
            String name,
            String type,
            boolean required,
            String description
    ) {
    }

    /** 提供给 Python 模型的单个 Action 工具卡。 */
    public record ToolAction(
            String actionId,
            String title,
            String description,
            RiskLevel risk,
            List<ToolParameter> parameters
    ) {
        public ToolAction {
            parameters = copy(parameters);
        }
    }

    /** 提供给 Python 模型的单个 Query 工具卡。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolQuery(
            String queryId,
            String title,
            String description,
            List<ToolParameter> parameters,
            JsonNode outputSchema,
            QueryDisplayContract display
    ) {
        public ToolQuery {
            parameters = copy(parameters);
        }

        /** 兼容尚未声明结果结构和展示建议的旧调用。 */
        public ToolQuery(
                String queryId,
                String title,
                String description,
                List<ToolParameter> parameters
        ) {
            this(queryId, title, description, parameters, null, null);
        }
    }

    /** AI 根据只读 Query 结果返回的受控展示描述。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryView(
            int sourceQuery,
            String layout,
            String title,
            String rowPath,
            List<ViewField> fields,
            List<ViewField> detailFields,
            String emptyText,
            String density,
            String groupBy,
            Boolean hideRepeatedFields,
            List<String> emphasisFields
    ) {
        public QueryView {
            fields = copy(fields);
            detailFields = copy(detailFields);
            emphasisFields = copy(emphasisFields);
        }

        /** 兼容尚未声明扩展展示语义的旧响应。 */
        public QueryView(
                int sourceQuery,
                String layout,
                String title,
                String rowPath,
                List<ViewField> fields,
                String emptyText
        ) {
            this(
                    sourceQuery,
                    layout,
                    title,
                    rowPath,
                    fields,
                    List.of(),
                    emptyText,
                    "comfortable",
                    "none",
                    false,
                    List.of()
            );
        }
    }

    /** 展示描述中的单个已有字段引用。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ViewField(
            String key,
            String label,
            String format,
            String role
    ) {
        public ViewField(String key, String label, String format) {
            this(key, label, format, null);
        }
    }

    /** 按模块分组提供给 Python 模型的启用工具卡。 */
    public record ToolModule(
            String moduleId,
            String title,
            String description,
            List<ToolAction> actions,
            List<ToolQuery> queries
    ) {
        public ToolModule {
            actions = copy(actions);
            queries = copy(queries);
        }
    }

    /** Java 发往 Python 的首次自然语言回合请求。 */
    public record ServiceTurnRequest(
            String goal,
            String conversationId,
            String clientRequestId,
            boolean aiPlanEnabled,
            List<ToolModule> modules
    ) {
        public ServiceTurnRequest {
            modules = copy(modules);
        }
    }

    /** Java 将受限的本地 Query 结果回传 Python 的续聊请求。 */
    public record ServiceQueryContinuationRequest(
            String goal,
            String conversationId,
            String clientRequestId,
            boolean aiPlanEnabled,
            List<ToolModule> modules,
            List<QueryResult> queryResults
    ) {
        public ServiceQueryContinuationRequest {
            modules = copy(modules);
            queryResults = copy(queryResults);
        }
    }

    /**
     * Python AI 服务返回的受控决策。
     *
     * <p>该对象只表示建议的 {@code ACTIONS}、{@code PLAN}、{@code QUERY} 或
     * {@code QUESTION}。Java 仍负责模块启用、参数、授权、风险、执行和回滚校验。</p>
     */
    public record ServiceDecision(
            String kind,
            String conversationId,
            String message,
            List<Action> actions,
            List<QueryCall> queries,
            Question question,
            QueryView view
    ) {
        public ServiceDecision {
            actions = copy(actions);
            queries = copy(queries);
        }

        /** 兼容既有四种决策的构造调用。 */
        public ServiceDecision(
                String kind,
                String conversationId,
                String message,
                List<Action> actions,
                List<QueryCall> queries,
                Question question
        ) {
            this(kind, conversationId, message, actions, queries, question, null);
        }
    }

    /** 对可空集合做不可变复制，保留 {@code null} 与空集合的契约差异。 */
    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    /** 复制动作参数；省略参数时以空对象继续后续契约校验。 */
    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
