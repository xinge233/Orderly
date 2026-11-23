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
import com.orderly.host.modulecontrol.ModuleControlInterfaces;
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

/**
 * 自然语言目标到受控决策的唯一宿主编排入口。
 *
 * <p>该服务只把启用且允许 AI 调用的模块工具卡交给 Python，并根据受控响应执行
 * Query、生成独立计划或调用宿主 Action 执行核心。模型或 IPC 失败不会回退到
 * Java 本地规划器；所有 Action 仍由 {@link AiActionService} 做最终校验和担保。</p>
 */
@Service
public class AiInterpretService {

    /** 只缓存短时间内的完成结果，用于抵御前端重试而不形成长期会话存储。 */
    private static final Duration REPLAY_TTL = Duration.ofMinutes(10);
    /** 与自然语言回合契约保持一致的目标文本上限。 */
    private static final int MAX_GOAL_LENGTH = 4000;
    /** 防止去重键和续聊派生键无限增长。 */
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 200;

    private final AiServiceClient aiService;
    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final AiActionService actions;
    private final AiPlanService plans;
    private final SystemFeatureService systemFeatures;
    private final AiQueryService queries;
    /** 仅在当前进程内保存已完成回合及同一请求 ID 的并发锁。 */
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

    /**
     * 处理一个自然语言回合。
     *
     * <p>每个请求必须有 {@code clientRequestId}。相同请求 ID 在设置版本一致时，
     * 只允许重放同一目标和会话的首次结果，防止前端重试造成重复写入；不同目标或
     * 会话复用该 ID 会返回冲突。进程内锁将同一请求 ID 的并发调用串行化，重放缓存
     * 只保留短时间，且这些步骤共享同一个 {@link AiServiceClient.TurnBudget}。</p>
     */
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

    /** 确认一个仍处于预览期内的独立 AI 计划。 */
    public ExecutionRecord confirmPlan(String planId) {
        return plans.confirm(planId);
    }

    /** 组织首次 IPC 回合，并把 Python 的纯建议转换成 Java 宿主响应。 */
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

    /**
     * 按决策类型分派后续处理。
     *
     * <p>Query 结果完成本地查询后始终携带受限结果回传 Python。续聊响应禁止再次
     * 产生 Query，避免形成不受控的查询循环；模型调用失败时仍返回本地结果。</p>
     */
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
                AiContracts.ServiceDecision continuation;
                try {
                    continuation = aiService.continueWithQueryResults(
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
                } catch (RuntimeException exception) {
                    yield queryResponse(
                            conversationId,
                            decision,
                            queryResults,
                            "AI 展示整理不可用，已显示本地查询结果。"
                    );
                }
                String continuationConversationId = requireConversationId(
                        continuation.conversationId(),
                        conversationId
                );
                try {
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
                } catch (RuntimeException exception) {
                    if ("VIEW".equalsIgnoreCase(continuation.kind())) {
                        yield queryResponse(
                                conversationId,
                                decision,
                                queryResults,
                                "AI 展示整理无效，已显示本地查询结果。"
                        );
                    }
                    throw exception;
                }
            }
            case "VIEW" -> {
                if (queryAllowed) {
                    throw invalidResponse("VIEW 只能用于 Query 结果展示");
                }
                yield viewResponse(conversationId, decision, attachedQueryResults);
            }
            default -> throw invalidResponse("AI service 返回了未知 kind");
        };
    }

    /** 校验并转换 Python 的澄清问题，不执行任何业务动作。 */
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

    /** 将 Python 提议的动作预检为可选 AI 计划，确认前不执行。 */
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

    /**
     * 执行或降级 Python 提议的动作数组。
     *
     * <p>先由宿主预检全部动作。包含删除且未开启删除直执时，计划功能开启则转为
     * 独立计划确认，否则只返回删除确认问题；只有通过该策略后才进入统一 Action
     * 执行核心，由其继续处理风险、确认、快照、审计和回滚。</p>
     */
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

    /** 返回已完成的本地只读查询结果，明确表示没有执行动作。 */
    private AiContracts.TurnResponse queryResponse(
            String conversationId,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults
    ) {
        return queryResponse(conversationId, decision, queryResults, null);
    }

    /** 返回本地 Query 结果；fallbackMessage 用于明确说明 AI 展示整理不可用。 */
    private AiContracts.TurnResponse queryResponse(
            String conversationId,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults,
            String fallbackMessage
    ) {
        String message = defaultMessage(
                fallbackMessage,
                defaultMessage(
                decision.message(),
                "已完成本地查询，未执行任何动作。"
                )
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

    /**
     * 校验 AI 返回的展示描述，并只附带已经执行的本地 Query 结果。
     */
    private AiContracts.TurnResponse viewResponse(
            String conversationId,
            AiContracts.ServiceDecision decision,
            List<AiContracts.QueryResult> queryResults
    ) {
        AiContracts.QueryView view = decision.view();
        if (view == null) {
            throw invalidResponse("VIEW 缺少 view");
        }
        if (queryResults == null || queryResults.isEmpty()) {
            throw invalidResponse("VIEW 缺少可展示的 Query 结果");
        }
        if (view.sourceQuery() < 1 || view.sourceQuery() > queryResults.size()) {
            throw invalidResponse("VIEW.sourceQuery 超出 Query 结果范围");
        }
        AiContracts.QueryResult source = queryResults.get(view.sourceQuery() - 1);
        if (source.status() != AiContracts.QueryStatus.OK) {
            throw invalidResponse("VIEW 只能引用成功的 Query 结果");
        }
        String layout = canonicalViewLayout(view.layout());
        String title = normalizedRequired(view.title(), 200, "VIEW.title");
        String rowPath = normalize(view.rowPath());
        if (rowPath != null && !validFieldPath(rowPath)) {
            throw invalidResponse("VIEW.rowPath 只能使用点分隔字段名");
        }
        String emptyText = normalize(view.emptyText());
        if (emptyText != null && emptyText.length() > 200) {
            throw invalidResponse("VIEW.emptyText 不能超过 200 个字符");
        }
        List<AiContracts.ViewField> fields = view.fields() == null
                ? List.of()
                : view.fields();
        if (fields.size() > 12) {
            throw invalidResponse("VIEW.fields 不能超过 12 项");
        }
        Object sourceNode = source.payload();
        boolean rowPathPresent = rowPath == null || hasPath(sourceNode, rowPath);
        Object rowNode = rowPath == null ? sourceNode : readPath(sourceNode, rowPath);
        if (List.of("list", "table").contains(layout)) {
            if (rowPath == null) {
                throw invalidResponse("VIEW 的 list/table 布局需要 rowPath");
            }
            if (!rowPathPresent || !(rowNode instanceof List<?>)) {
                throw invalidResponse("VIEW.rowPath 必须指向数组");
            }
        }
        Object sample = rowNode instanceof List<?> list && !list.isEmpty()
                ? list.getFirst()
                : rowNode;
        List<AiContracts.ViewField> validatedFields = new ArrayList<>();
        for (AiContracts.ViewField field : fields) {
            if (field == null) {
                throw invalidResponse("VIEW.fields 不能包含空项");
            }
            String key = normalizedRequired(field.key(), 160, "VIEW.fields.key");
            if (!validFieldPath(key)) {
                throw invalidResponse("VIEW.fields.key 只能使用点分隔字段名");
            }
            String label = normalizedRequired(field.label(), 80, "VIEW.fields.label");
            String format = normalizedRequired(field.format(), 32, "VIEW.fields.format");
            if (!List.of("text", "status", "datetime", "number", "boolean")
                    .contains(format)) {
                throw invalidResponse("VIEW.fields.format 非法");
            }
            if (sample != null && !hasPath(sample, key)) {
                throw invalidResponse("VIEW.fields 引用了不存在的字段：" + key);
            }
            validatedFields.add(new AiContracts.ViewField(key, label, format));
        }
        AiContracts.QueryView validated = new AiContracts.QueryView(
                view.sourceQuery(),
                layout,
                title,
                rowPath,
                validatedFields,
                emptyText
        );
        return new AiContracts.TurnResponse(
                "VIEW",
                UUID.randomUUID().toString(),
                conversationId,
                defaultMessage(decision.message(), "已整理本地查询结果。"),
                RiskLevel.READ_ONLY,
                null,
                decision.queries(),
                queryResults,
                validated,
                null,
                null,
                null
        );
    }

    /** 读取点分隔字段路径；不存在时返回 null。 */
    private Object readPath(Object source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** 判断点分隔路径是否存在；字段值本身为 null 时仍视为存在。 */
    private boolean hasPath(Object source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return false;
            }
            current = map.get(part);
        }
        return true;
    }

    /** 接受模型给出的常见大小写，但返回前端契约要求的规范布局值。 */
    private String canonicalViewLayout(String value) {
        String normalized = normalizedRequired(value, 32, "VIEW.layout");
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "keyvalue" -> "keyValue";
            case "list" -> "list";
            case "table" -> "table";
            case "text" -> "text";
            default -> throw invalidResponse("VIEW.layout 非法");
        };
    }

    private boolean validFieldPath(String value) {
        return value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");
    }

    private String normalizedRequired(String value, int maximum, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw invalidResponse(field + " 不能为空");
        }
        if (normalized.length() > maximum) {
            throw invalidResponse(field + " 不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    /** 删除直执关闭且不能转为计划时，向前端返回不执行删除的澄清结果。 */
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

    /**
     * 从契约目录生成 Python 可见的工具卡。
     *
     * <p>业务模块必须启用；受保护 {@code module_system_control} 只暴露宿主白名单治理接口。
     * Action/Query 只受模块治理动态开关约束。工具卡元数据来自
     * {@link ContractCatalog}，这里不维护第二套定义。</p>
     */
    private List<AiContracts.ToolModule> enabledToolModules() {
        List<AiContracts.ToolModule> cards = new ArrayList<>();
        List<ModuleContract> discoverable = new ArrayList<>(catalog.businessModules());
        discoverable.addAll(catalog.systemModules().stream()
                .filter(module -> ModuleControlInterfaces.MODULE_ID.equals(module.moduleId()))
                .toList());
        for (ModuleContract module : discoverable) {
            if (module.system() == null && !moduleControl.isEnabled(module.moduleId())) {
                continue;
            }
            List<AiContracts.ToolAction> actions = module.actions().stream()
                    .filter(action -> moduleControl.isActionAiCallableEnabled(
                            module.moduleId(),
                            action.id()
                    ))
                    .map(this::toolAction)
                    .toList();
            List<AiContracts.ToolQuery> queries = module.queries().stream()
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

    /** 将权威 Action 契约映射成模型可读的工具卡。 */
    private AiContracts.ToolAction toolAction(ActionContract action) {
        return new AiContracts.ToolAction(
                action.id(),
                action.title(),
                action.description(),
                action.risk(),
                parameters(action.parameters())
        );
    }

    /** 将权威 Query 契约映射成模型可读的工具卡。 */
    private AiContracts.ToolQuery toolQuery(QueryContract query) {
        return new AiContracts.ToolQuery(
                query.id(),
                query.title(),
                query.description(),
                parameters(query.parameters()),
                query.outputSchema(),
                query.display()
        );
    }

    /** 复制契约参数声明，保留必填、类型和说明供模型规划。 */
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

    /** 将 IPC 动作 DTO 映射为统一 Action 执行核心的调用对象。 */
    private List<AiActionService.ActionCall> toCalls(List<AiContracts.Action> actions) {
        return actions.stream()
                .map(action -> new AiActionService.ActionCall(
                        action.moduleId(),
                        action.actionId(),
                        action.arguments()
                ))
                .toList();
    }

    /** 要求 Action/Plan 决策至少包含一项动作，具体内容留给执行核心校验。 */
    private void requireActions(AiContracts.ServiceDecision decision) {
        if (decision.actions() == null || decision.actions().isEmpty()) {
            throw invalidResponse(decision.kind() + " 必须包含 actions");
        }
    }

    /** 校验 Python 返回的会话 ID，并阻止已有会话被切换到另一个会话。 */
    private String requireConversationId(String actual, String expected) {
        if (actual == null || actual.isBlank()) {
            throw invalidResponse("AI service 决策缺少 conversationId");
        }
        if (expected != null && !expected.equals(actual)) {
            throw invalidResponse("AI service 返回了不一致的 conversationId");
        }
        return actual;
    }

    /**
     * 为 Query 结果续聊生成有长度上限的内部请求 ID。
     *
     * <p>后缀由宿主固定添加，只用于同一首轮请求的受控续聊，不能作为新的独立回合
     * 绕过客户端请求 ID 约束。</p>
     */
    private String continuationRequestId(String clientRequestId) {
        String suffix = "#query-results";
        int limit = MAX_CLIENT_REQUEST_ID_LENGTH - suffix.length();
        String prefix = clientRequestId.length() <= limit
                ? clientRequestId
                : clientRequestId.substring(0, limit);
        return prefix + suffix;
    }

    /** 清理已超过重放窗口的回合结果，避免进程内缓存持续增长。 */
    private void pruneReplays(Instant now) {
        replayedTurns.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue().expiresAt())
        );
    }

    /** 去除输入首尾空白，并把空字符串统一视为未提供。 */
    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    /** 模型未给出可用消息时使用稳定的宿主提示语。 */
    private String defaultMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.trim();
    }

    /** 构造“上游违反 AI 决策契约”的统一网关错误。 */
    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }

    /** 已成功完成的回合重放记录，包含请求指纹和到期时间。 */
    private record ReplayedTurn(
            String goal,
            String requestedConversationId,
            AiContracts.TurnResponse response,
            Instant expiresAt
    ) {
    }

    /** 以请求 ID 和设置代次区分可安全重放的回合。 */
    private record ReplayKey(String clientRequestId, int settingsVersion) {
    }
}
