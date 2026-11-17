package com.orderly.host.ai;

import java.util.List;

/** 可替换的模型供应商客户端。 */
public interface AiProviderClient {

    String complete(
            AiProviderConfig config,
            String systemPrompt,
            List<AiChatMessage> messages
    );

    default String test(AiProviderConfig config) {
        return complete(
                config,
                "你只需要确认连接可用。",
                List.of(new AiChatMessage("user", "请只回复 OK。"))
        );
    }
}
