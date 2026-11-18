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

/** 将宿主 AI 请求隔离到本地 Python 服务；Java 不保存模型凭据或会话。 */
@Component
public class AiServiceClient {

    private static final Set<String> TURN_KINDS =
            Set.of("ACTIONS", "PLAN", "QUESTION", "QUERY");
    private static final Set<String> FOLLOW_UP_KINDS =
            Set.of("ACTIONS", "PLAN", "QUESTION");

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;
    private final HttpClient httpClient;
    private final URI serviceRoot;
    private final Duration requestTimeout;
    private final Duration turnTimeout;
    private final String ipcToken;

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

    public AiContracts.SettingsView settings() {
        return settings(requestTimeout);
    }

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

    public AiContracts.SettingsView clearSettings() {
        JsonNode body = requestJson("DELETE", "/v1/settings", null, requestTimeout);
        try {
            return objectMapper.treeToValue(body, AiContracts.SettingsView.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI settings 响应格式非法");
        }
    }

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

    public AiContracts.ServiceDecision createTurn(AiContracts.ServiceTurnRequest request) {
        return createTurn(request, turnTimeout);
    }

    public AiContracts.ServiceDecision createTurn(
            AiContracts.ServiceTurnRequest request,
            Duration timeout
    ) {
        JsonNode body = requestJson("POST", "/v1/turns", request, timeout);
        AiContracts.ServiceDecision decision = decision(body);
        return validateDecision(decision, true);
    }

    public AiContracts.ServiceDecision continueWithQueryResults(
            AiContracts.ServiceQueryContinuationRequest request
    ) {
        return continueWithQueryResults(request, turnTimeout);
    }

    public AiContracts.ServiceDecision continueWithQueryResults(
            AiContracts.ServiceQueryContinuationRequest request,
            Duration timeout
    ) {
        JsonNode body = requestJson("POST", "/v1/turns/query-results", request, timeout);
        AiContracts.ServiceDecision decision = decision(body);
        return validateDecision(decision, false);
    }

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

    private AiContracts.ServiceDecision decision(JsonNode body) {
        try {
            return strictMapper.treeToValue(body, AiContracts.ServiceDecision.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("AI service 决策格式非法");
        }
    }

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

    public record TurnBudget(Instant deadline) {
        public Duration remaining() {
            Duration duration = Duration.between(Instant.now(), deadline);
            return duration.isNegative() ? Duration.ZERO : duration;
        }
    }
}
