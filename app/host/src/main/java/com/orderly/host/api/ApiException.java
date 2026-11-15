package com.orderly.host.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** 可安全返回给前端的业务异常。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details == null ? Map.of() : details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
