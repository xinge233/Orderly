package com.orderly.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 模块执行结果。
 *
 * @param message 可审计摘要
 * @param output 结构化输出
 */
public record ModuleResult(String message, Map<String, Object> output) {
    public ModuleResult {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("模块结果摘要不能为空");
        }
        output = Map.copyOf(Objects.requireNonNullElse(output, Map.of()));
    }

    public static ModuleResult completed(String message) {
        return new ModuleResult(message, Map.of());
    }
}
