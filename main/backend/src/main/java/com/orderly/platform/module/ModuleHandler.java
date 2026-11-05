package com.orderly.platform.module;

/**
 * 声明模块可接收受控命令的扩展接口。
 *
 * <p>模块必须先显式声明动作支持范围，随后才能接收命令。
 * 该双阶段设计让调度层可以在执行前拒绝未知动作，也便于未来插入
 * 风险分级、确认和审计代理。</p>
 */
public interface ModuleHandler {

    /**
     * 判断模块是否声明支持某个动作。
     *
     * @param action 动作名称
     * @return 支持时返回 {@code true}
     */
    boolean supports(String action);

    /**
     * 处理一条已经路由到本模块的命令。
     *
     * @param command 统一命令信封
     * @return 结构化处理结果
     */
    ModuleCommandResult handle(ModuleCommand command);
}
