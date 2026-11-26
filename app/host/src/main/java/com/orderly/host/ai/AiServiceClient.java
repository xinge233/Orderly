package com.orderly.host.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将宿主 AI 请求隔离到本地 Python 服务。
 *
 * <p>IPC 目标必须是 {@code http://127.0.0.1:<port>}，禁止重定向、路径、凭据和远端
 * 主机。正常启动时 {@code scripts/start.ps1} 每次运行生成随机 Bearer Token，并通过
 * 环境变量注入 Java 与 Python；本客户端只把该令牌放入请求头，不写日志或响应。
 * Java 不保存模型 API Key 或会话，模型凭据只在 Python 进程内存中。</p>
 *
 * <p>连接、单请求和整回合分别受配置时限约束；一次回合的后续设置、首次请求和
 * Query 续聊共享总截止时间，避免分段调用无限延长。</p>
 */
@Component
public class AiServiceClient {

    /** 首次回合允许返回的决策种类。 */
    private static final Set<String> TURN_KINDS =
            Set.of("ACTIONS", "PLAN", "QUESTION", "QUERY");
    /** Query 结果续聊允许的最终决策种类，明确禁止再次 Query。 */
    private static final Set<String> FOLLOW_UP_KINDS =
            Set.of("ACTIONS", "PLAN", "QUESTION", "VIEW");

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;
    private final HttpClient httpClient;
    private final URI serviceRoot;
    private final Duration requestTimeout;
    private final Duration turnTimeout;
    private final String ipcToken;

    /**
     * 创建仅访问本机 AI 服务的 HTTP 客户端。
     *
     * <p>决策响应使用开启未知字段失败的严格 mapper；HTTP 客户端关闭自动重定向，
     * 防止回环请求被引导到其他主机。</p>
     */
    public AiServiceClient(
            ObjectMapper objectMapper,
            @Value("${orderly.ai-service.url:http://127.0.0.1:18080}") String serviceUrl,
            @Value("${orderly.ai-service.connect-timeout:2s}") Duration connectTimeout,
            @Value("${orderly.ai-service.request-timeout:15s}") Duration requestTimeout,
            @Value("${orderly.ai-service.turn-timeout:60s}") Duration turnTimeout,
            @Value("${ORDERLY_AI_IPC_TOKEN:}") String ipcToken
    ) {
        this.objectMapper = objectMapper;
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.serviceRoot = serviceRoot(serviceUrl);
        this.requestTimeout = requestTimeout;
        this.turnTimeout = turnTimeout;
        this.ipcToken = ipcToken == null ? "" : ipcToken.trim();
    }

    /** 使用标准请求时限读取 Python 内存中的 AI 设置。 */
    public AiContracts.SettingsView settings() {
        return settings(requestTimeout);
    }

    /**
     * 在给定剩余预算内读取设置，并校验响应至少包含提供方模式。
     */
    public AiContracts.SettingsView settings(Duration timeout) {
        JsonNode body = requestJson("GET", "/v1/settings", null, timeout);
        try {
            AiContracts.SettingsView view = objectMapper.treeToValue(
                    body,
                    AiContracts.SettingsView.class
            );
            if (view.mode() == null) {
                throw invalidResponse("AI settings 响应缺少 mode");
            }
            return view;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI settings 响应格式非法");
        }
    }

    /**
     * 将设置写入 Python 内存。
     *
     * <p>请求可能包含临时 API Key；本方法不记录请求体，Python 返回的
     * {@link AiContracts.SettingsView} 也不含 Key。</p>
     */
    public AiContracts.SettingsView updateSettings(AiContracts.SettingsRequest request) {
        if (request == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AI_SETTINGS",
                    "AI 设置不能为空"
            );
        }
        JsonNode body = requestJson("PUT", "/v1/settings", request, requestTimeout);
        try {
            AiContracts.SettingsView view = objectMapper.treeToValue(
                    body,
                    AiContracts.SettingsView.class
            );
            if (view.mode() == null) {
                throw invalidResponse("AI settings 响应缺少 mode");
            }
            return view;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI settings 响应格式非法");
        }
    }

    /** 清除 Python 进程中的模型配置，并返回不含凭据的最新设置视图。 */
    public AiContracts.SettingsView clearSettings() {
        JsonNode body = requestJson("DELETE", "/v1/settings", null, requestTimeout);
        try {
            return objectMapper.treeToValue(body, AiContracts.SettingsView.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI settings 响应格式非法");
        }
    }

    /** 使用 Python 内存中的当前配置执行一次受控连通性测试。 */
    public AiContracts.ConnectionTest testSettings() {
        JsonNode body = requestJson("POST", "/v1/settings/test", null, requestTimeout);
        try {
            AiContracts.ConnectionTest result = objectMapper.treeToValue(
                    body,
                    AiContracts.ConnectionTest.class
            );
            if (result.message() == null || result.message().isBlank()) {
                throw invalidResponse("AI 连接测试响应缺少 message");
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI 连接测试响应格式非法");
        }
    }

    /** 使用整回合默认时限发送首次 AI 回合。 */
    public AiContracts.ServiceDecision createTurn(AiContracts.ServiceTurnRequest request) {
        return createTurn(request, turnTimeout);
    }

    /**
     * 发送首次 AI 回合，并对 Python 返回的结构化决策做宿主侧契约校验。
     */
    public AiContracts.ServiceDecision createTurn(
            AiContracts.ServiceTurnRequest request,
            Duration timeout
    ) {
        JsonNode body = requestJson("POST", "/v1/turns", request, timeout);
        AiContracts.ServiceDecision decision = decision(body);
        return validateDecision(decision, true);
    }

    /** 使用剩余整回合时限发送 Query 结果续聊。 */
    public AiContracts.ServiceDecision continueWithQueryResults(
            AiContracts.ServiceQueryContinuationRequest request
    ) {
        return continueWithQueryResults(request, turnTimeout);
    }

    /**
     * 将经用户设置允许的受限 Query 结果回传 Python。
     *
     * <p>续聊只允许返回 {@code ACTIONS}、{@code PLAN} 或 {@code QUESTION}，不能再次
     * 产生 Query，从而在宿主侧终止查询循环。</p>
     */
    public AiContracts.ServiceDecision continueWithQueryResults(
            AiContracts.ServiceQueryContinuationRequest request,
            Duration timeout
    ) {
        JsonNode body = requestJson("POST", "/v1/turns/query-results", request, timeout);
        AiContracts.ServiceDecision decision = decision(body);
        return validateDecision(decision, false);
    }

    /** 为一次完整 AI 回合创建不可延长的总截止时间。 */
    public TurnBudget beginTurn() {
        return new TurnBudget(Instant.now().plus(turnTimeout));
    }

    /** 仅用于健康页的只读探针，不返回服务地址、令牌或错误正文。 */
    public String health() {
        try {
            Duration timeout = requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    ? requestTimeout
                    : Duration.ofSeconds(1);
            HttpResponse<Void> response = send(
                    "GET",
                    "/v1/health",
                    null,
                    timeout,
                    HttpResponse.BodyHandlers.discarding()
            );
            return response.statusCode() >= 200 && response.statusCode() < 300 ? "UP" : "DOWN";
        } catch (RuntimeException exception) {
            return "DOWN";
        }
    }

    /** 以短时只读探针判断 Python 是否已持有可用模型配置。 */
    public boolean configured() {
        try {
            Duration timeout = requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    ? requestTimeout
                    : Duration.ofSeconds(1);
            JsonNode body = requestJson("GET", "/v1/settings", null, timeout);
            return objectMapper.treeToValue(body, AiContracts.SettingsView.class).configured();
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 规范化并限制 Python 服务根地址。
     *
     * <p>只接受 HTTP、字面回环地址 {@code 127.0.0.1} 和显式端口；主机名、HTTPS、
     * 用户信息、额外路径、查询参数和片段全部拒绝，避免 IPC 离开本机或改变根路径。</p>
     */
    static URI serviceRoot(String serviceUrl) {
        String normalized = String.valueOf(serviceUrl == null ? "" : serviceUrl).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("orderly.ai-service.url 格式非法", exception);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1
                || uri.getPort() > 65535) {
            throw new IllegalStateException(
                    "orderly.ai-service.url 必须使用 http://127.0.0.1:<port>"
            );
        }
        if (uri.getRawUserInfo() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isBlank())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "orderly.ai-service.url 不得包含凭据、路径、查询参数或片段"
            );
        }
        return URI.create(normalized);
    }

    /**
     * 发送 JSON IPC 请求并把非 2xx 状态映射为稳定宿主错误。
     */
    private JsonNode requestJson(String method, String path, Object payload, Duration timeout) {
        HttpResponse<String> response = send(
                method,
                path,
                payload,
                timeout,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstreamFailure(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            throw invalidResponse("AI service 返回空响应");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw invalidResponse("AI service 返回的不是合法 JSON");
        }
    }

    /**
     * 构造并异步发送 HTTP 请求，再由调用线程按剩余预算等待。
     *
     * <p>有载荷时设置 JSON 内容类型；IPC Token 非空时只作为 Authorization 请求头
     * 使用。中断状态会被恢复，连接失败和超时统一失败关闭为服务不可用。</p>
     */
    private <T> HttpResponse<T> send(
            String method,
            String path,
            Object payload,
            Duration timeout,
            HttpResponse.BodyHandler<T> bodyHandler
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw unavailable();
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (payload != null) {
            try {
                publisher = HttpRequest.BodyPublishers.ofByteArray(
                        objectMapper.writeValueAsBytes(payload)
                );
            } catch (JsonProcessingException exception) {
                throw invalidResponse("无法编码 AI service 请求");
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(serviceRoot.resolve(path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .method(method, publisher);
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (!ipcToken.isBlank()) {
            builder.header("Authorization", "Bearer " + ipcToken);
        }

        try {
            return httpClient.sendAsync(builder.build(), bodyHandler)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
    }

    /** 用严格 mapper 把 JSON 转为决策，拒绝契约外字段和原始类型 null。 */
    private AiContracts.ServiceDecision decision(JsonNode body) {
        try {
            return strictMapper.treeToValue(body, AiContracts.ServiceDecision.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI service 决策格式非法");
        }
    }

    /**
     * 在进入执行层前校验决策种类及其互斥字段。
     *
     * <p>首次回合允许 Query；携带 Query 结果的续聊禁止再次 Query。Action/Plan 必须有
     * 动作，Question 必须有具体问题，其他种类不得混入不适用字段。</p>
     */
    private AiContracts.ServiceDecision validateDecision(
            AiContracts.ServiceDecision decision,
            boolean queryAllowed
    ) {
        if (decision == null) {
            throw invalidResponse("AI service 未返回决策");
        }
        String kind = decision.kind() == null ? "" : decision.kind().trim();
        Set<String> allowed = queryAllowed ? TURN_KINDS : FOLLOW_UP_KINDS;
        if (!allowed.contains(kind)) {
            throw invalidResponse("AI service 返回了未知 kind");
        }
        if (decision.conversationId() == null || decision.conversationId().isBlank()) {
            throw invalidResponse("AI service 决策缺少 conversationId");
        }
        List<AiContracts.Action> actions = decision.actions();
        if (actions != null) {
            for (AiContracts.Action action : actions) {
                if (action == null || blank(action.moduleId()) || blank(action.actionId())) {
                    throw invalidResponse("AI service 返回的 Action 字段不完整");
                }
            }
        }
        List<AiContracts.QueryCall> queries = decision.queries();
        if ("QUERY".equals(kind)) {
            if (queries == null || queries.isEmpty() || queries.size() > 4) {
                throw invalidResponse("QUERY 必须包含 1 至 4 条查询");
            }
            for (AiContracts.QueryCall query : queries) {
                if (query == null || blank(query.moduleId()) || blank(query.queryId())) {
                    throw invalidResponse("QUERY 字段不完整");
                }
            }
        } else if (queries != null && !queries.isEmpty()) {
            throw invalidResponse("只有 QUERY 决策可以包含 queries");
        }
        if (!("ACTIONS".equals(kind) || "PLAN".equals(kind)) && actions != null) {
            throw invalidResponse("只有 ACTIONS 或 PLAN 决策可以包含 actions");
        }
        if (!"QUERY".equals(kind) && queries != null) {
            throw invalidResponse("只有 QUERY 决策可以包含 queries");
        }
        if (!"QUESTION".equals(kind) && decision.question() != null) {
            throw invalidResponse("只有 QUESTION 决策可以包含 question");
        }
        if (!"VIEW".equals(kind) && decision.view() != null) {
            throw invalidResponse("只有 VIEW 决策可以包含 view");
        }
        if ("VIEW".equals(kind)) {
            if (decision.view() == null) {
                throw invalidResponse("VIEW 缺少 view");
            }
            if (actions != null || queries != null || decision.question() != null) {
                throw invalidResponse("VIEW 不得混入动作、查询或澄清字段");
            }
            AiContracts.QueryView view = decision.view();
            if (view.sourceQuery() < 1 || view.sourceQuery() > 4) {
                throw invalidResponse("VIEW.sourceQuery 必须在 1 至 4 之间");
            }
            if (blank(view.layout()) || blank(view.title())) {
                throw invalidResponse("VIEW 缺少 layout 或 title");
            }
        }
        if ("QUESTION".equals(kind)) {
            AiContracts.Question question = decision.question();
            if (question == null || blank(question.prompt())) {
                throw invalidResponse("QUESTION 缺少 question.prompt");
            }
        }
        if (("ACTIONS".equals(kind) || "PLAN".equals(kind))
                && (actions == null || actions.isEmpty())) {
            throw invalidResponse(kind + " 必须包含 actions");
        }
        return decision;
    }

    /**
     * 将 Python 错误映射为安全的上游失败。
     *
     * <p>只透传格式合法的 4xx 业务码和消息；非法响应保留明确的 502 语义，其余
     * 5xx、不可解析响应或未批准错误码统一收敛为 503，避免泄漏上游细节。</p>
     */
    private ApiException upstreamFailure(int statusCode, String body) {
        JsonNode root = null;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException ignored) {
            // Invalid upstream errors are surfaced as service unavailable below.
        }
        String code = text(root, "code");
        String message = text(root, "message");
        if (root != null && root.path("error").isObject()) {
            if (blank(code)) {
                code = text(root.path("error"), "code");
            }
            if (blank(message)) {
                message = text(root.path("error"), "message");
            }
        }
        if (statusCode == 502 && "AI_INVALID_RESPONSE".equals(code)) {
            return invalidResponse("AI service 返回了非法决策");
        }
        if (statusCode == 502 && "AI_PROVIDER_UNAVAILABLE".equals(code)) {
            return new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    code,
                    blank(message)
                            ? "模型服务暂时不可用，请稍后重试。"
                            : message
            );
        }
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status != null && status.is4xxClientError()
                && !blank(code) && code.matches("[A-Z0-9_]{1,80}")) {
            return new ApiException(
                    status,
                    code,
                    blank(message) ? "AI service 拒绝了请求" : message
            );
        }
        return unavailable();
    }

    private String text(JsonNode root, String field) {
        return root == null ? "" : root.path(field).asText("").trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_SERVICE_UNAVAILABLE",
                "AI 服务暂不可用"
        );
    }

    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }

    /**
     * 单次 AI 回合的共享截止时间。
     *
     * <p>{@link #remaining()} 只会返回非负剩余时长；归零后后续 IPC 请求失败关闭，
     * 不能用新的单请求超时延长整个回合。</p>
     */
    public record TurnBudget(Instant deadline) {
        public Duration remaining() {
            Duration duration = Duration.between(Instant.now(), deadline);
            return duration.isNegative() ? Duration.ZERO : duration;
        }
    }
}
