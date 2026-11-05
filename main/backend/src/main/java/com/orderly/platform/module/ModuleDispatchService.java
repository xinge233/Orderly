package com.orderly.platform.module;

import org.springframework.stereotype.Service;

/**
 * 模块间命令调度的最小骨架。
 *
 * <p>当前实现只路由没有副作用的测试模块动作。任何未来的文件或系统动作都必须
 * 在进入该服务之前完成计划确认、权限边界检查，并在执行后写入审计与快照。</p>
 */
@Service
public class ModuleDispatchService {

    private final ModuleRegistry registry;

    /**
     * 注入模块注册表。
     *
     * @param registry 平台模块目录
     */
    public ModuleDispatchService(ModuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * 将命令路由到显式声明支持该动作的模块。
     *
     * @param command 统一命令
     * @return 统一结果；找不到模块或动作时返回拒绝结果
     */
    public ModuleCommandResult dispatch(ModuleCommand command) {
        PlatformModule module = registry.find(command.moduleId()).orElse(null);
        if (module == null) {
            return ModuleCommandResult.rejected("目标模块未注册：" + command.moduleId());
        }
        if (!(module instanceof ModuleHandler handler)) {
            return ModuleCommandResult.rejected("目标模块未声明命令处理能力");
        }
        if (!handler.supports(command.action())) {
            return ModuleCommandResult.rejected("目标模块不支持动作：" + command.action());
        }
        return handler.handle(command);
    }
}
