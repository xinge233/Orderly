package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;

/**
 * 服务端计划确认边界。
 *
 * <p>实现必须依据控制层保存的确认记录判断，不得信任前端的布尔值。
 * 当前骨架没有确认接口，因此任何声明需要确认的动作都会被拒绝。</p>
 */
@FunctionalInterface
public interface PlanConfirmationGuard {

    /**
     * 判断动作是否已经获得服务端确认。
     *
     * @param request 执行请求
     * @param action 动作定义
     * @return 已确认或无需确认时返回 true
     */
    boolean isConfirmed(ExecutionRequest request, ModuleActionDefinition action);
}
