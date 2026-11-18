package com.orderly.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 模块单次正向执行或逆操作的结构化结果。
 *
 * <p>摘要用于事件审计和用户提示，输出供宿主持久化并可在后续有序 Action 中通过
 * 结构化引用读取。两者都必须可序列化为 JSON，不得承载无法审计的内部句柄。</p>
 *
 * @param message 可审计摘要
 * @param output 结构化输出
 */
public record ModuleResult(String message, Map<String, Object> output) {
    /**
     * 校验摘要并复制顶层输出映射。
     *
     * <p>空白摘要会立即失败；{@code null} 输出按空映射处理。这里只复制映射层级，
     * 不递归冻结嵌套 JSON 值，也不产生审计或业务副作用。</p>
     */
    public ModuleResult {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("模块结果摘要不能为空");
        }
        output = Map.copyOf(Objects.requireNonNullElse(output, Map.of()));
    }

    /** 创建无结构化输出的完成结果；调用方仍需由宿主记录成功状态。 */
    public static ModuleResult completed(String message) {
        return new ModuleResult(message, Map.of());
    }
}
