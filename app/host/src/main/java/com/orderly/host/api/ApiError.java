package com.orderly.host.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/** 统一错误响应。 */
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), Map.of());
    }

    public static ApiError of(String code, String message, Map<String, Object> details) {
        return new ApiError(
                code,
                message,
                Instant.now(),
                details == null ? Map.of() : Map.copyOf(details)
        );
    }
}
