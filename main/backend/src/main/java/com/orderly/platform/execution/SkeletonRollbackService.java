package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;
import org.springframework.stereotype.Component;

/**
 * 骨架阶段的保守回滚实现。
 *
 * <p>当前没有任何真实副作用，因此不会修改文件。未来接入真实工具时，
 * 该实现必须根据快照执行受控恢复，而不是由模块自行处理。</p>
 */
@Component
public class SkeletonRollbackService implements RollbackService {

    @Override
    public RollbackResult rollback(
            ExecutionRequest request,
            ModuleActionDefinition action,
            ExecutionSnapshot snapshot,
            String reason
    ) {
        return RollbackResult.notRequired("当前动作没有真实副作用，无需执行回滚。");
    }
}
