package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;
import org.springframework.stereotype.Component;

/**
 * 骨架阶段的无副作用快照与审计实现。
 *
 * <p>它保持管线阶段和调用边界完整，但不访问文件或数据库。接入真实存储时
 * 必须替换此实现，同时保留中央管线的调用顺序。</p>
 */
@Component
public class NoopSnapshotAuditService implements SnapshotAuditService {

    @Override
    public ExecutionSnapshot prepare(ExecutionRequest request, ModuleActionDefinition action) {
        return ExecutionSnapshot.empty();
    }

    @Override
    public void record(ExecutionAuditEvent event) {
        // 当前阶段没有持久化审计，明确保留为空实现。
    }
}
