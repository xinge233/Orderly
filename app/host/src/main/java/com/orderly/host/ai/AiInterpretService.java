package com.orderly.host.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 自然语言目标到 ACTIONS、PLAN 或 QUESTION 的唯一宿主入口。 */
@Service
public class AiInterpretService {

    private static final Duration CONVERSATION_TTL = Duration.ofMinutes(10);
    private static final int MAX_GOAL_LENGTH = 4000;
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 200;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final AiSettingsService settings;
    private final AiProviderClient client;
    private final AiPromptFactory prompts;
    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final AiActionService actions;
    private final AiPlanService plans;
    private final SystemFeatureService systemFeatures;
    private final ObjectMapper objectMapper;
    private final AiContextEpoch contextEpoch;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, ReplayedTurn> replayedTurns = new ConcurrentHashMap<>();
    private final Map<String, Object> requestLocks = new ConcurrentHashMap<>();

    public AiInterpretService(
            AiSettingsService settings,
            AiProviderClient client,
            AiPromptFactory prompts,
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            AiActionService actions,
            AiPlanService plans,
            SystemFeatureService systemFeatures,
            ObjectMapper objectMapper,
            AiContextEpoch contextEpoch
    ) {
        this.settings = settings;
        this.client = client;
        this.prompts = prompts;
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.actions = actions;
        this.plans = plans;
        this.systemFeatures = systemFeatures;
        this.objectMapper = objectMapper;
        this.contextEpoch = contextEpoch;
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

        String requestedConversationId = normalizeRequestedConversationId(request.conversationId());
        String clientRequestId = normalizeClientRequestId(request.clientRequestId());
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

        long epoch = contextEpoch.current();
        String replayKey = replayKey(epoch, clientRequestId);
        Object requestLock = requestLocks.computeIfAbsent(replayKey, ignored -> new Object());
        try {
            synchronized (requestLock) {
                Instant now = Instant.now();
                pruneTransientState(now, epoch);
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
                String conversationId = requestedConversationId == null
                        ? UUID.randomUUID().toString()
                        : requestedConversationId;
                AiContracts.TurnResponse response = processTurn(
                        goal,
                        conversationId,
                        epoch
                );
                replayedTurns.put(
                        replayKey,
                        new ReplayedTurn(
                                goal,
                                response,
                                requestedConversationId,
                                Instant.now().plus(CONVERSATION_TTL),
                                epoch
                        )
                );
                return response;
            }
        } finally {
            requestLocks.remove(replayKey, requestLock);
        }
    }

    private AiContracts.TurnResponse processTurn(
            String goal,
            String conversationId,
            long epoch
    ) {
        AiProviderConfig config = settings.requireConfigured();
        Instant now = Instant.now();
        pruneTransientState(now, epoch);
        Conversation previous = conversations.get(conversationId);
        if (previous == null || now.isAfter(previous.expiresAt())) {
            previous = new Conversation(
                    conversationId,
                    List.of(),
                    now.plus(CONVERSATION_TTL),
                    epoch
            );
        }

        List<AiChatMessage> messages = new ArrayList<>(previous.messages());
        messages.add(new AiChatMessage("user", goal));
        boolean aiPlanEnabled = systemFeatures.aiPlansEnabled();
        String systemPrompt = prompts.systemPrompt(
                enabledModules(),
                config.allowDeleteDirect(),
                aiPlanEnabled
        );
        String raw = completeWithRepair(config, systemPrompt, messages, aiPlanEnabled);
        AiContracts.ModelDecision decision = parseDecision(raw, aiPlanEnabled);
        List<AiChatMessage> nextMessages = new ArrayList<>(messages);
        nextMessages.add(new AiChatMessage("assistant", raw));
        if (nextMessages.size() > MAX_HISTORY_MESSAGES) {
            nextMessages = new ArrayList<>(
                    nextMessages.subList(
                            nextMessages.size() - MAX_HISTORY_MESSAGES,
                            nextMessages.size()
                    )
            );
        }
        List<AiChatMessage> history = List.copyOf(nextMessages);
        Optional<AiContracts.TurnResponse> currentResponse = contextEpoch.runIfCurrent(
                epoch,
                () -> {
                    conversations.put(
                            conversationId,
                            new Conversation(
                                    conversationId,
                                    history,
                                    now.plus(CONVERSATION_TTL),
                                    epoch
                            )
                    );
                    return switch (decision.kind()) {
            case "QUESTION" -> questionResponse(conversationId, decision);
            case "PLAN" -> {
                if (!aiPlanEnabled) {
                    throw invalidResponse("AI 计划返回已关闭");
                }
                yield planResponse(conversationId, goal, decision);
            }
            case "ACTIONS" -> actionResponse(
                    conversationId,
                    goal,
                    decision,
                    config,
                    aiPlanEnabled
            );
            default -> throw invalidResponse("模型返回了未知 kind");
                    };
                }
        );
        return currentResponse.orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT,
                "AI_SETTINGS_CHANGED",
                "AI 设置已变更，本次请求未执行；请重新提交"
        ));
    }

    public ExecutionRecord confirmPlan(String planId) {
        return plans.confirm(planId);
    }

    private AiContracts.TurnResponse questionResponse(
            String conversationId,
            AiContracts.ModelDecision decision
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
                question,
                null
        );
    }

    private AiContracts.TurnResponse planResponse(
            String conversationId,
            String goal,
            AiContracts.ModelDecision decision
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
                plan,
                null,
                null
        );
    }

    private AiContracts.TurnResponse actionResponse(
            String conversationId,
            String goal,
            AiContracts.ModelDecision decision,
            AiProviderConfig config,
            boolean aiPlanEnabled
    ) {
        requireActions(decision);
        List<AiActionService.ActionCall> calls = toCalls(decision.actions());
        AiActionService.Preview preview = actions.preview(calls);

        if (preview.highestRisk() == RiskLevel.DELETE && !config.allowDeleteDirect()) {
            if (!aiPlanEnabled) {
                return deleteConfirmationQuestion(conversationId);
            }
            AiContracts.Plan plan = plans.create(goal, decision.actions());
            return new AiContracts.TurnResponse(
                    "PLAN",
                    UUID.randomUUID().toString(),
                    conversationId,
                    "删除动作需要确认，已转为 AI 计划。",
                    plan.highestRisk(),
                    null,
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
                null,
                record
        );
    }

    private AiContracts.TurnResponse deleteConfirmationQuestion(String conversationId) {
        return new AiContracts.TurnResponse(
                "QUESTION",
                UUID.randomUUID().toString(),
                conversationId,
                "删除直执未开启，本次没有执行删除。",
                null,
                null,
                null,
                new AiContracts.Question(
                        "删除直执未开启。请先开启删除直执，或取消删除动作。",
                        List.of("取消删除", "开启删除直执")
                ),
                null
        );
    }

    private String completeWithRepair(
            AiProviderConfig config,
            String systemPrompt,
            List<AiChatMessage> messages,
            boolean aiPlanEnabled
    ) {
        String raw;
        try {
            raw = client.complete(config, systemPrompt, messages);
        } catch (AiProviderException exception) {
            throw providerUnavailable(exception);
        }
        try {
            parseDecision(raw, aiPlanEnabled);
            return raw;
        } catch (RuntimeException firstFailure) {
            List<AiChatMessage> repairMessages = new ArrayList<>(messages);
            repairMessages.add(new AiChatMessage("assistant", raw));
            String allowedKinds = aiPlanEnabled
                    ? "ACTIONS、PLAN 或 QUESTION"
                    : "ACTIONS 或 QUESTION";
            String planConstraint = aiPlanEnabled ? "" : "，也不要返回计划";
            repairMessages.add(new AiChatMessage(
                    "user",
                    "上一条输出不符合当前执行策略。请只返回一个 JSON 对象，"
                            + "kind 只能是 " + allowedKinds + "，不要 Markdown"
                            + planConstraint + "。"
            ));
            try {
                String repaired = client.complete(config, systemPrompt, repairMessages);
                parseDecision(repaired, aiPlanEnabled);
                return repaired;
            } catch (AiProviderException exception) {
                throw providerUnavailable(exception);
            } catch (RuntimeException ignored) {
                throw invalidResponse(aiPlanEnabled
                        ? "模型返回无法解析为受控结构，请重试"
                        : "AI 计划返回已关闭，模型仍未返回 ACTIONS 或 QUESTION");
            }
        }
    }

    private AiContracts.ModelDecision parseDecision(String raw, boolean aiPlanEnabled) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(raw));
        } catch (JsonProcessingException exception) {
            throw invalidResponse("模型输出不是合法 JSON");
        }
        String kind = root.path("kind").asText("").trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIONS", "PLAN", "QUESTION").contains(kind)) {
            throw invalidResponse("模型输出 kind 非法");
        }
        if ("PLAN".equals(kind) && !aiPlanEnabled) {
            throw invalidResponse("AI 计划返回已关闭");
        }
        String message = root.path("message").asText("");
        List<AiContracts.Action> actions = parseActions(root.path("actions"));
        AiContracts.Question question = parseQuestion(root.path("question"));
        return new AiContracts.ModelDecision(kind, message, actions, question);
    }

    private List<AiContracts.Action> parseActions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalidResponse("actions 必须是数组");
        }
        List<AiContracts.Action> result = new ArrayList<>();
        for (JsonNode item : node) {
            String moduleId = item.path("moduleId").asText("").trim();
            String actionId = item.path("actionId").asText("").trim();
            JsonNode argumentsNode = item.path("arguments");
            if (moduleId.isBlank() || actionId.isBlank() || !argumentsNode.isObject()) {
                throw invalidResponse("Action 必须包含 moduleId、actionId 和 arguments");
            }
            Map<String, Object> arguments = objectMapper.convertValue(
                    argumentsNode,
                    JSON_OBJECT
            );
            result.add(new AiContracts.Action(moduleId, actionId, arguments));
        }
        return result;
    }

    private AiContracts.Question parseQuestion(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String prompt = node.path("prompt").asText("").trim();
        List<String> options = new ArrayList<>();
        JsonNode optionsNode = node.path("options");
        if (optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                if (option.isTextual() && !option.asText().isBlank()) {
                    options.add(option.asText().trim());
                } else if (option.isObject()) {
                    String label = option.path("label").asText("").trim();
                    if (!label.isBlank()) {
                        options.add(label);
                    }
                }
            }
        }
        return new AiContracts.Question(prompt, options);
    }

    private void requireActions(AiContracts.ModelDecision decision) {
        if (decision.actions().isEmpty()) {
            throw invalidResponse(decision.kind() + " 必须包含 actions");
        }
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

    private List<ModuleContract> enabledModules() {
        return catalog.businessModules().stream()
                .filter(module -> moduleControl.isEnabled(module.moduleId()))
                .map(module -> new ModuleContract(
                        module.moduleId(),
                        module.title(),
                        module.description(),
                        module.order(),
                        module.system(),
                        module.actions().stream()
                                .filter(ActionContract::aiCallable)
                                .filter(action -> moduleControl.isActionAiCallableEnabled(
                                        module.moduleId(),
                                        action.id()
                                ))
                                .toList(),
                        module.queries(),
                        module.plannerRules()
                ))
                .filter(module -> !module.actions().isEmpty())
                .toList();
    }

    private String normalizeRequestedConversationId(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeClientRequestId(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String replayKey(long epoch, String clientRequestId) {
        return epoch + "\u0000" + clientRequestId;
    }

    private void pruneTransientState(Instant now, long epoch) {
        conversations.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue().expiresAt())
                        || entry.getValue().epoch() != epoch
        );
        replayedTurns.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue().expiresAt())
                        || entry.getValue().epoch() != epoch
        );
    }

    private String stripCodeFence(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            if (firstLine >= 0) {
                value = value.substring(firstLine + 1);
            }
            int lastFence = value.lastIndexOf("```");
            if (lastFence >= 0) {
                value = value.substring(0, lastFence);
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            value = value.substring(start, end + 1);
        }
        return value.trim();
    }

    private String defaultMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.trim();
    }

    private ApiException providerUnavailable(AiProviderException exception) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_PROVIDER_UNAVAILABLE",
                exception.getMessage()
        );
    }

    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }

    private record Conversation(
            String conversationId,
            List<AiChatMessage> messages,
            Instant expiresAt,
            long epoch
    ) {
        private Conversation {
            messages = List.copyOf(messages);
        }
    }

    private record ReplayedTurn(
            String goal,
            AiContracts.TurnResponse response,
            String requestedConversationId,
            Instant expiresAt,
            long epoch
    ) {
    }
}
