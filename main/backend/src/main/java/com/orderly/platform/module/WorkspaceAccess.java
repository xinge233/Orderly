package com.orderly.platform.module;

/**
 * 动作声明的用户工作区访问范围。
 *
 * <p>当前骨架默认只放行 {@link #NONE}。未来真实文件模块必须由中央授权服务
 * 提供工作区范围，模块不能自行决定路径是否合法。</p>
 */
public enum WorkspaceAccess {
    NONE,
    READ,
    WRITE
}
