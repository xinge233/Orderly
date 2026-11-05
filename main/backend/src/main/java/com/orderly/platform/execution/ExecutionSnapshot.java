package com.orderly.platform.execution;

import java.util.Map;
import java.util.Objects;

/**
 * 执行前后的最小快照描述。
 *
 * <p>当前只允许保存结构化元数据，不读取真实文件。未来文件模块必须由
 * 控制层快照服务扩展此结构，模块不能自行写入任意路径内容。</p>
 *
 * @param metadata 只读快照元数据
 */
public record ExecutionSnapshot(Map<String, Object> metadata) {
    public ExecutionSnapshot {
        metadata = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
    }

    /**
     * 创建空快照。
     *
     * @return 不含副作用的快照
     */
    public static ExecutionSnapshot empty() {
        return new ExecutionSnapshot(Map.of());
    }
}
