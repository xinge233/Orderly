package com.orderly.host.ai;

/** 模型供应商连接失败；消息不得包含凭据或原始请求体。 */
public class AiProviderException extends RuntimeException {

    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
