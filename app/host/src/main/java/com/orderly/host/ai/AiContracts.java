package com.orderly.host.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.kernel.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 全局 AI 接入、查询、会话和独立计划接口使用的稳定数据结构。 */
public final class AiContracts {

    private AiContracts() {
    }

    public enum Mode {
        DEEPSEEK,
        CUSTOM
    }

    public record SettingsRequest(
            Mode mode,
            String apiKey,
            String baseUrl,
            String model,
            Boolean allowDeleteDirect,
            Boolean allowSendQueryResultsToModel
    ) {
        @Override
        public String toString() {
            return "SettingsRequest["
                    + "mode=" + mode
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? null : "[redacted]")
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", allowDeleteDirect=" + allowDeleteDirect
                    + ", allowSendQueryResultsToModel=" + allowSendQueryResultsToModel
                    + "]";
        }
    }

    public record SettingsView(
            Mode mode,
            String baseUrl,
            String model,
            boolean configured,
            boolean allowDeleteDirect,
            boolean allowSendQueryResultsToModel,
            boolean allowLocalProvider,
            int settingsVersion
    ) {
    }

    public record ConnectionTest(boolean ok, String message) {
    }

    public record TurnRequest(
            String goal,
            String conversationId,
            String clientRequestId
    ) {
    }

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
            Plan plan,
            Question question,
            ExecutionRecord execution
    ) {
        public TurnResponse {
            actions = copy(actions);
            queries = copy(queries);
            queryResults = copy(queryResults);
        }
    }

    public record Action(
            String moduleId,
            String actionId,
            Map<String, Object> arguments
    ) {
        public Action {
            arguments = copyMap(arguments);
        }
    }

    public record QueryCall(
            String moduleId,
            String queryId,
            Map<String, Object> arguments
    ) {
        public QueryCall {
            arguments = copyMap(arguments);
        }
    }

    public enum QueryStatus {
        OK,
        FAILED,
        TRUNCATED
    }

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

    public record Question(
            String prompt,
            List<String> options
    ) {
        public Question {
            options = copy(options);
        }
    }

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

    public record ToolParameter(
            String name,
            String type,
            boolean required,
            String description
    ) {
    }

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

    public record ToolQuery(
            String queryId,
            String title,
            String description,
            List<ToolParameter> parameters
    ) {
        public ToolQuery {
            parameters = copy(parameters);
        }
    }

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

    /** Python AI 服务返回的受控决策；Java 不信任其中的执行状态。 */
    public record ServiceDecision(
            String kind,
            String conversationId,
            String message,
            List<Action> actions,
            List<QueryCall> queries,
            Question question
    ) {
        public ServiceDecision {
            actions = copy(actions);
            queries = copy(queries);
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
