package com.orderly.host.api;

import java.time.Instant;

/** 统一错误响应。 */
public record ApiError(
        String code,
        String message,
        Instant timestamp
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
