package com.orderly.platform.module;

import com.orderly.platform.execution.ExecutionPermit;

import java.util.List;

/**
 * 声明模块动作并提供受控执行入口。
 *
 * <p>动作集合是唯一可信来源：注册表据此建立动作索引，执行管线据此完成
 * 参数校验、确认和边界检查。模块执行时必须收到中央管线签发的一次性
 * {@link ExecutionPermit}，普通调用方不能自行构造该许可。</p>
 */
public interface ModuleHandler {

    /**
     * 返回模块声明支持的全部动作。
     *
     * @return 不可修改的动作定义列表
     */
    List<ModuleActionDefinition> actions();

    /**
     * 处理已经通过中央管线校验的命令。
     *
     * @param command 统一命令信封
     * @param permit 中央管线签发的一次性执行许可
     * @return 结构化处理结果
     */
    ModuleCommandResult execute(ModuleCommand command, ExecutionPermit permit);
}
