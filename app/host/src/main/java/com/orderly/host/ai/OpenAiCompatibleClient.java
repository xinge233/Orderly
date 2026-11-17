package com.orderly.host.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用 OpenAI 兼容 Chat Completions 协议的模型客户端。 */
@Component
public class OpenAiCompatibleClient implements AiProviderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String complete(
            AiProviderConfig config,
            String systemPrompt,
            List<AiChatMessage> messages
    ) {
        if (!config.configured()) {
            throw new AiProviderException("AI 尚未配置 API Key。");
        }
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        payloadMessages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage message : messages) {
            payloadMessages.add(Map.of(
                    "role", message.role(),
                    "content", message.content()
            ));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("messages", payloadMessages);
        payload.put("temperature", 0.1);
        payload.put("stream", false);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint(config.baseUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload)
                    ))
                    .build();
        } catch (IOException exception) {
            throw new AiProviderException("无法构造模型请求。", exception);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("模型请求被中断。", exception);
        } catch (IOException exception) {
            throw new AiProviderException("无法连接模型服务。", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiProviderException(
                    "模型服务返回 HTTP " + response.statusCode() + "。"
            );
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
            }
            if (content.isObject() || content.isArray()) {
                return objectMapper.writeValueAsString(content);
            }
            throw new AiProviderException("模型服务返回空内容。");
        } catch (IOException exception) {
            throw new AiProviderException("模型服务返回无法解析的响应。", exception);
        }
    }

    static URI endpoint(String baseUrl) {
        String normalized = String.valueOf(baseUrl == null ? "" : baseUrl).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }
}
