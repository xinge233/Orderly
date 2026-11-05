package com.orderly.platform.module;

/**
 * 所有平台模块必须实现的最小元数据契约。
 *
 * <p>该接口不包含执行方法，因此只读模块、AI 计划模块和未来的文件工具模块
 * 都可以先通过相同方式被发现。需要处理受控动作的模块再单独实现
 * {@link ModuleHandler}，从而保持“能被发现”和“能执行动作”两种能力的松耦合。</p>
 */
public interface PlatformModule {

    /**
     * 返回模块自身的稳定描述。
     *
     * @return 模块元数据
     */
    ModuleDescriptor descriptor();
}
