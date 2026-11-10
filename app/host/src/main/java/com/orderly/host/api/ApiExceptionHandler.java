package com.orderly.host.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.file.InvalidPathException;

/** 把业务异常转换为稳定的结构化响应，不泄露服务端堆栈。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidPathException.class})
    public ResponseEntity<ApiError> handleIllegalArgument(RuntimeException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", safeMessage(exception, "请求参数非法")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", "请求字段校验失败"));
    }

    /**
     * SSE 客户端主动关闭连接是正常生命周期，不应再尝试写入 JSON 错误体。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClosedEventStream() {
        // 异步响应已不可用，交由容器完成连接清理。
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务处理失败，请查看本机日志"));
    }

    private String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
