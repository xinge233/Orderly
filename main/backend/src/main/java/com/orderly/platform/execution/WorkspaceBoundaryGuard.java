package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;

/**
 * 用户授权工作区边界。
 *
 * <p>真实实现必须解析规范化路径、验证符号链接并确保目标位于授权根目录内。
 * 当前骨架不会访问文件，只允许无需工作区的动作。</p>
 */
@FunctionalInterface
public interface WorkspaceBoundaryGuard {

    /**
     * 判断动作是否可以使用所需工作区范围。
     *
     * @param request 执行请求
     * @param action 动作定义
     * @return 授权通过时返回 true
     */
    boolean isAuthorized(ExecutionRequest request, ModuleActionDefinition action);
}
