package com.orderly.host.ai;

import com.orderly.host.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

/** 当前进程内的 AI 连接配置；API Key 永不写入磁盘或响应。 */
@Service
public class AiSettingsService {

    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_MODEL = "DeepSeek-V4.1-Flash";

    private final AiProviderClient client;
    private final boolean allowLocalProvider;
    private final AiContextEpoch contextEpoch;
    private AiProviderConfig config = new AiProviderConfig(
            AiContracts.Mode.DEEPSEEK,
            DEEPSEEK_BASE_URL,
            DEEPSEEK_MODEL,
            null,
            false
    );

    public AiSettingsService(
            AiProviderClient client,
            @Value("${orderly.ai.allow-local-provider:false}") boolean allowLocalProvider,
            AiContextEpoch contextEpoch
    ) {
        this.client = client;
        this.allowLocalProvider = allowLocalProvider;
        this.contextEpoch = contextEpoch;
    }

    public synchronized AiContracts.SettingsView view() {
        return new AiContracts.SettingsView(
                config.mode(),
                config.baseUrl(),
                config.model(),
                config.configured(),
                config.allowDeleteDirect(),
                allowLocalProvider
        );
    }

    public synchronized AiContracts.SettingsView configure(
            AiContracts.SettingsRequest request
    ) {
        if (request == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AI_SETTINGS",
                    "AI 设置不能为空"
            );
        }
        AiContracts.Mode mode = request.mode() == null ? config.mode() : request.mode();
        String apiKey = normalizeKey(request.apiKey(), config.apiKey());
        if (apiKey == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_API_KEY_REQUIRED",
                    "请输入 API Key"
            );
        }
        boolean allowDeleteDirect = request.allowDeleteDirect() == null
                ? config.allowDeleteDirect()
                : request.allowDeleteDirect();

        String baseUrl;
        String model;
        if (mode == AiContracts.Mode.DEEPSEEK) {
            baseUrl = DEEPSEEK_BASE_URL;
            model = DEEPSEEK_MODEL;
        } else {
            if ((request.baseUrl() == null || request.baseUrl().isBlank())
                    && config.mode() != AiContracts.Mode.CUSTOM) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "AI_BASE_URL_REQUIRED",
                        "自定义模式需要 API Base URL"
                );
            }
            if ((request.model() == null || request.model().isBlank())
                    && config.mode() != AiContracts.Mode.CUSTOM) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "AI_MODEL_REQUIRED",
                        "自定义模式需要 Model"
                );
            }
            baseUrl = normalizeBaseUrl(request.baseUrl(), config.baseUrl());
            model = normalizeModel(request.model(), config.model());
            validateProviderUrl(baseUrl);
        }
        contextEpoch.change(() -> config = new AiProviderConfig(
                mode,
                baseUrl,
                model,
                apiKey,
                allowDeleteDirect
        ));
        return view();
    }

    public synchronized void clear() {
        contextEpoch.change(() -> config = new AiProviderConfig(
                AiContracts.Mode.DEEPSEEK,
                DEEPSEEK_BASE_URL,
                DEEPSEEK_MODEL,
                null,
                false
        ));
    }

    public synchronized AiProviderConfig requireConfigured() {
        if (!config.configured()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AI_NOT_CONFIGURED",
                    "AI 尚未接入，请先输入 API Key"
            );
        }
        return config;
    }

    public synchronized AiContracts.ConnectionTest testConnection() {
        AiProviderConfig current = requireConfigured();
        try {
            client.test(current);
            return new AiContracts.ConnectionTest(true, "模型连接成功");
        } catch (AiProviderException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AI_PROVIDER_UNAVAILABLE",
                    exception.getMessage()
            );
        }
    }

    private String normalizeKey(String requested, String existing) {
        String value = requested == null || requested.isBlank() ? existing : requested.trim();
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeBaseUrl(String requested, String existing) {
        String value = requested == null || requested.isBlank() ? existing : requested.trim();
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_BASE_URL_REQUIRED",
                    "自定义模式需要 API Base URL"
            );
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String normalizeModel(String requested, String existing) {
        String value = requested == null || requested.isBlank() ? existing : requested.trim();
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_MODEL_REQUIRED",
                    "自定义模式需要 Model"
            );
        }
        return value;
    }

    private void validateProviderUrl(String baseUrl) {
        final URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidUrl("API Base URL 格式非法");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalidUrl("API Base URL 必须只包含协议、主机和可选路径");
        }
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return;
        }
        boolean localHost = isLocalHost(uri.getHost());
        if (allowLocalProvider && "http".equals(scheme) && localHost) {
            return;
        }
        throw invalidUrl(
                allowLocalProvider
                        ? "自定义 API 只允许 HTTPS，或开发开关下的 localhost HTTP"
                        : "自定义 API 必须使用 HTTPS"
        );
    }

    private boolean isLocalHost(String host) {
        String normalized = String.valueOf(host).toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private ApiException invalidUrl(String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "AI_INVALID_BASE_URL",
                message
        );
    }
}
