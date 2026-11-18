package com.orderly.host.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应。
 *
 * <p>只包含可安全返回前端的错误码、消息、时间戳和可选结构化详情，不携带服务端
 * 堆栈、上游原始响应或模型凭据。</p>
 */
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details
) {
    /** 创建无附加详情、时间戳为当前时刻的错误响应。 */
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), Map.of());
    }

    /** 创建带不可变详情的错误响应；{@code null} 详情按空对象处理。 */
    public static ApiError of(String code, String message, Map<String, Object> details) {
        return new ApiError(
                code,
                message,
                Instant.now(),
                details == null ? Map.of() : Map.copyOf(details)
        );
    }
}
