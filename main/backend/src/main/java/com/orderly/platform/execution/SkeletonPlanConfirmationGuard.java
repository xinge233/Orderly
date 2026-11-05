package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;
import org.springframework.stereotype.Component;

/**
 * 骨架阶段的保守确认策略。
 *
 * <p>只读动作无需确认；写入和破坏性动作在真正接入服务端确认存储前一律拒绝。</p>
 */
@Component
public class SkeletonPlanConfirmationGuard implements PlanConfirmationGuard {

    @Override
    public boolean isConfirmed(ExecutionRequest request, ModuleActionDefinition action) {
        return !action.confirmationRequired();
    }
}
