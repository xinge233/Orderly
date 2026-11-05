package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;
import com.orderly.platform.module.WorkspaceAccess;
import org.springframework.stereotype.Component;

/**
 * 骨骼阶段的工作区边界。
 *
 * <p>在没有正式工作区授权服务前，只放行不访问工作区的动作。
 * 这一默认拒绝策略确保未来新增文件动作不会意外绕过路径校验。</p>
 */
@Component
public class SkeletonWorkspaceBoundaryGuard implements WorkspaceBoundaryGuard {

    @Override
    public boolean isAuthorized(ExecutionRequest request, ModuleActionDefinition action) {
        return action.workspaceAccess() == WorkspaceAccess.NONE;
    }
}
