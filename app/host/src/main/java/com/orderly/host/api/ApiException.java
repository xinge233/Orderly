package com.orderly.host.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 可安全返回给前端的业务异常。
 *
 * <p>异常同时携带 HTTP 状态、稳定业务码和可选结构化详情，由统一异常处理器转换成
 * {@link ApiError}。调用方必须只构造适合暴露给本地前端的消息，不能把堆栈或密钥
 * 放入详情。</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    /** 创建无附加详情的业务异常。 */
    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    /** 创建带不可变详情的业务异常；{@code null} 详情按空对象处理。 */
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

    /** 返回该错误对应的 HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 返回供前端分支处理的稳定大写错误码。 */
    public String code() {
        return code;
    }

    /** 返回只读的结构化错误详情。 */
    public Map<String, Object> details() {
        return details;
    }
}
