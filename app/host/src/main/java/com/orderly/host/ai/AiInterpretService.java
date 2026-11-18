package com.orderly.host.ai;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.system.SystemFeatureService;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 自然语言目标到受控决策的唯一宿主编排入口。 */
@Service
public class AiInterpretService {

    private static final Duration REPLAY_TTL = Duration.ofMinutes(10);
    private static final int MAX_GOAL_LENGTH = 4000;
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 200;

    private final AiServiceClient aiService;
    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final AiActionService actions;
    private final AiPlanService plans;
    private final SystemFeatureService systemFeatures;
    private final AiQueryService queries;
    private final Map<ReplayKey, ReplayedTurn> replayedTurns = new ConcurrentHashMap<>();
    private final Map<String, Object> requestLocks = new ConcurrentHashMap<>();

    public AiInterpretService(
            AiServiceClient aiService,
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            AiActionService actions,
            AiPlanService plans,
            SystemFeatureService systemFeatures,
            AiQueryService queries
    ) {
        this.aiService = aiService;
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.actions = actions;
        this.plans = plans;
        this.systemFeatures = systemFeatures;
        this.queries = queries;
    }

    public AiContracts.TurnResponse interpret(AiContracts.TurnRequest request) {
        if (request == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AI_TURN",
                    "请求体不能为空"
            );
        }
        String goal = String.valueOf(request.goal() == null ? "" : request.goal()).trim();
        if (goal.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_GOAL_REQUIRED",
                    "请输入自然语言指令"
            );
        }
        if (goal.length() > MAX_GOAL_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_GOAL_TOO_LONG",
                    "自然语言指令不能超过 " + MAX_GOAL_LENGTH + " 个字符"
            );
        }

        String requestedConversationId = normalize(request.conversationId());
        String clientRequestId = normalize(request.clientRequestId());
        if (clientRequestId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_CLIENT_REQUEST_ID_REQUIRED",
                    "AI 回合必须提供 clientRequestId，以避免重复执行"
            );
        }
        if (clientRequestId.length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_CLIENT_REQUEST_ID_TOO_LONG",
                    "clientRequestId 不能超过 " + MAX_CLIENT_REQUEST_ID_LENGTH + " 个字符"
            );
        }

        Object requestLock = requestLocks.computeIfAbsent(clientRequestId, ignored -> new Object());
        try {
            synchronized (requestLock) {
                AiServiceClient.TurnBudget budget = aiService.beginTurn();
                boolean aiPlanEnabled = systemFeatures.aiPlansEnabled();
                AiContracts.SettingsView settings = aiService.settings(budget.remaining());
                ReplayKey replayKey = new ReplayKey(
                        clientRequestId,
                        settings.settingsVersion()
                );
                Instant now = Instant.now();
                pruneReplays(now);
                ReplayedTurn replay = replayedTurns.get(replayKey);
                if (replay != null && now.isBefore(replay.expiresAt())) {
                    if (!replay.goal().equals(goal)
                            || !Objects.equals(
                                    replay.requestedConversationId(),
                                    requestedConversationId
                            )) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "AI_CLIENT_REQUEST_ID_CONFLICT",
                                "同一 clientRequestId 不能用于不同的指令或会话"
                        );
                    }
                    return replay.response();
                }
                AiContracts.TurnResponse response = processTurn(
                        goal,
                        requestedConversationId,
                        clientRequestId,
                        budget,
                        settings,
                        aiPlanEnabled
                );
                replayedTurns.put(
                        replayKey,
                        new ReplayedTurn(
                                goal,
                                requestedConversationId,
                                response,
                                Instant.now().plus(REPLAY_TTL)
                        )
                );
                return response;
            }
        } finally {
            requestLocks.remove(clientRequestId, requestLock);
        }
    }

    public ExecutionRecord confirmPlan(String planId) {
        return plans.confirm(planId);
    }

    private AiContracts.TurnResponse processTurn(
            String goal,
            String requestedConversationId,
            String clientRequestId,
            AiServiceClient.TurnBudget budget,
            AiContracts.SettingsView settings,
            boolean aiPlanEnabled
    ) {
        List<AiContracts.ToolModule> modules = enabledToolModules();
        AiContracts.ServiceTurnRequest serviceRequest = new AiContracts.ServiceTurnRequest(
                goal,
                requestedConversationId,
                clientRequestId,
                aiPlanEnabled,
                modules
        );
        AiContracts.ServiceDecision decision = aiService.createTurn(
                serviceRequest,
                budget.remaining()
        );
        String conversationId = requireConversationId(
                decision.conversationId(),
                requestedConversationId
        );
        return processDecision(
                conversationId,
                goal,
                clientRequestId,
                decision,
                settings,
                aiPlanEnabled,
                modules,
                List.of(),
                true,
                budget
        );
    }

    private AiContracts.TurnResponse processDecision(
            String conversationId,
            String goal,
            String clientRequestId,
            AiContracts.ServiceDecision decision,
            AiContracts.SettingsView settings,
            boolean aiPlanEnabled,
            List<AiContracts.ToolModule> modules,
            List<AiContracts.QueryResult> attachedQueryResults,
            boolean queryAllowed,
            AiServiceClient.TurnBudget budget
    ) {
        String kind = decision.kind() == null
                ? ""
                : decision.kind().trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "QUESTION" -> questionResponse(
                    conversationId,
                    decision,
                    attachedQueryResults
            );
            case "PLAN" -> {
                if (!aiPlanEnabled) {
                    throw invalidResponse("AI 计划返回已关闭");
                }
                yield planResponse(conversationId, goal, decision, attachedQueryResults);
            }
            case "ACTIONS" -> actionResponse(
                    conversationId,
                    goal,
                    decision,
                    settings,
                    aiPlanEnabled,
                    attachedQueryResults
            );
            case "QUERY" -> {
                if (!queryAllowed) {
                    throw invalidResponse("查询结果回合不允许再次返回 QUERY");
                }
                List<AiContracts.QueryResult> queryResults =
                        queries.executeAiQueries(decision.queries());
                if (!settings.allowSendQueryResultsToModel()) {
                    yield queryResponse(
                            conversationId,
                            decision,
                            queryResults
                    );
                }
                AiContracts.ServiceDecision continuation = aiService.continueWithQueryResults(
                        new AiContracts.ServiceQueryContinuationRequest(
                                goal,
                                conversationId,
                                continuationRequestId(clientRequestId),
                                aiPlanEnabled,
                                modules,
                                queryResults
                        ),
                        budget.remaining()
                );
                String continuationConversationId = requireConversationId(
                        continuation.conversationId(),
                        conversationId
                );
                yield processDecision(
                        continuationConversationId,
                        goal,
                        clientRequestId,
                        continuation,
                        settings,
                        aiPlanEnabled,
                        modules,
                        queryResults,
                        false,
                        budget
                );
            }
            default -> throw invalidResponse("AI service 返回了未知 kind");
        };
    }

    private AiContracts.TurnResponse questionResponse(
            String conversationId,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults
    ) {
        AiContracts.Question question = decision.question();
        if (question == null || question.prompt() == null || question.prompt().isBlank()) {
            throw invalidResponse("QUESTION 缺少具体问题");
        }
        return new AiContracts.TurnResponse(
                "QUESTION",
                UUID.randomUUID().toString(),
                conversationId,
                defaultMessage(decision.message(), "需要补充信息后才能继续。"),
                null,
                null,
                null,
                queryResults,
                null,
                question,
                null
        );
    }

    private AiContracts.TurnResponse planResponse(
            String conversationId,
            String goal,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults
    ) {
        requireActions(decision);
        AiContracts.Plan plan = plans.create(goal, decision.actions());
        return new AiContracts.TurnResponse(
                "PLAN",
                UUID.randomUUID().toString(),
                conversationId,
                defaultMessage(decision.message(), "已生成计划，请确认后执行。"),
                plan.highestRisk(),
                null,
                null,
                queryResults,
                plan,
                null,
                null
        );
    }

    private AiContracts.TurnResponse actionResponse(
            String conversationId,
            String goal,
            AiContracts.ServiceDecision decision,
            AiContracts.SettingsView settings,
            boolean aiPlanEnabled,
            List<AiContracts.QueryResult> queryResults
    ) {
        requireActions(decision);
        List<AiActionService.ActionCall> calls = toCalls(decision.actions());
        AiActionService.Preview preview = actions.preview(calls);

        if (preview.highestRisk() == RiskLevel.DELETE && !settings.allowDeleteDirect()) {
            if (!aiPlanEnabled) {
                return deleteConfirmationQuestion(conversationId, queryResults);
            }
            AiContracts.Plan plan = plans.create(goal, decision.actions());
            return new AiContracts.TurnResponse(
                    "PLAN",
                    UUID.randomUUID().toString(),
                    conversationId,
                    "删除动作需要确认，已转为 AI 计划。",
                    plan.highestRisk(),
                    null,
                    null,
                    queryResults,
                    plan,
                    null,
                    null
            );
        }

        ExecutionRecord record = actions.executeAiDirect(calls);
        return new AiContracts.TurnResponse(
                "ACTIONS",
                UUID.randomUUID().toString(),
                conversationId,
                defaultMessage(decision.message(), "动作已进入受控执行。"),
                preview.highestRisk(),
                decision.actions(),
                null,
                queryResults,
                null,
                null,
                record
        );
    }

    private AiContracts.TurnResponse queryResponse(
            String conversationId,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults
    ) {
        String message = defaultMessage(
                decision.message(),
                "已完成本地查询，未执行任何动作。"
        );
        return new AiContracts.TurnResponse(
                "QUERY",
                UUID.randomUUID().toString(),
                conversationId,
                message,
                RiskLevel.READ_ONLY,
                null,
                decision.queries(),
                queryResults,
                null,
                null,
                null
        );
    }

    private AiContracts.TurnResponse deleteConfirmationQuestion(
            String conversationId,
            List<AiContracts.QueryResult> queryResults
    ) {
        return new AiContracts.TurnResponse(
                "QUESTION",
                UUID.randomUUID().toString(),
                conversationId,
                "删除直执未开启，本次没有执行删除。",
                null,
                null,
                null,
                queryResults,
                null,
                new AiContracts.Question(
                        "删除直执未开启。请先开启删除直执，或取消删除动作。",
                        List.of("取消删除", "开启删除直执")
                ),
                null
        );
    }

    private List<AiContracts.ToolModule> enabledToolModules() {
        List<AiContracts.ToolModule> cards = new ArrayList<>();
        for (ModuleContract module : catalog.businessModules()) {
            if (!moduleControl.isEnabled(module.moduleId())) {
                continue;
            }
            List<AiContracts.ToolAction> actions = module.actions().stream()
                    .filter(ActionContract::aiCallable)
                    .filter(action -> moduleControl.isActionAiCallableEnabled(
                            module.moduleId(),
                            action.id()
                    ))
                    .map(this::toolAction)
                    .toList();
            List<AiContracts.ToolQuery> queries = module.queries().stream()
                    .filter(QueryContract::aiCallable)
                    .filter(query -> moduleControl.isQueryAiCallableEnabled(
                            module.moduleId(),
                            query.id()
                    ))
                    .map(this::toolQuery)
                    .toList();
            if (actions.isEmpty() && queries.isEmpty()) {
                continue;
            }
            cards.add(new AiContracts.ToolModule(
                    module.moduleId(),
                    module.title(),
                    module.description(),
                    actions,
                    queries
            ));
        }
        return List.copyOf(cards);
    }

    private AiContracts.ToolAction toolAction(ActionContract action) {
        return new AiContracts.ToolAction(
                action.id(),
                action.title(),
                action.description(),
                action.risk(),
                parameters(action.parameters())
        );
    }

    private AiContracts.ToolQuery toolQuery(QueryContract query) {
        return new AiContracts.ToolQuery(
                query.id(),
                query.title(),
                query.description(),
                parameters(query.parameters())
        );
    }

    private List<AiContracts.ToolParameter> parameters(List<ParameterContract> parameters) {
        return parameters.stream()
                .map(parameter -> new AiContracts.ToolParameter(
                        parameter.name(),
                        parameter.type(),
                        parameter.required(),
                        parameter.description()
                ))
                .toList();
    }

    private List<AiActionService.ActionCall> toCalls(List<AiContracts.Action> actions) {
        return actions.stream()
                .map(action -> new AiActionService.ActionCall(
                        action.moduleId(),
                        action.actionId(),
                        action.arguments()
                ))
                .toList();
    }

    private void requireActions(AiContracts.ServiceDecision decision) {
        if (decision.actions() == null || decision.actions().isEmpty()) {
            throw invalidResponse(decision.kind() + " 必须包含 actions");
        }
    }

    private String requireConversationId(String actual, String expected) {
        if (actual == null || actual.isBlank()) {
            throw invalidResponse("AI service 决策缺少 conversationId");
        }
        if (expected != null && !expected.equals(actual)) {
            throw invalidResponse("AI service 返回了不一致的 conversationId");
        }
        return actual;
    }

    private String continuationRequestId(String clientRequestId) {
        String suffix = "#query-results";
        int limit = MAX_CLIENT_REQUEST_ID_LENGTH - suffix.length();
        String prefix = clientRequestId.length() <= limit
                ? clientRequestId
                : clientRequestId.substring(0, limit);
        return prefix + suffix;
    }

    private void pruneReplays(Instant now) {
        replayedTurns.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue().expiresAt())
        );
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String defaultMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.trim();
    }

    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }

    private record ReplayedTurn(
            String goal,
            String requestedConversationId,
            AiContracts.TurnResponse response,
            Instant expiresAt
    ) {
    }

    private record ReplayKey(String clientRequestId, int settingsVersion) {
    }
}
