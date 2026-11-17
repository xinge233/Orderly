package com.orderly.host.ai;

/** OpenAI 兼容 Chat Completions 的单条消息。 */
public record AiChatMessage(String role, String content) {
}
