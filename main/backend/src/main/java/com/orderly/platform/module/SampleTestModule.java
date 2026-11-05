package com.orderly.platform.module;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用于验证模块注册与调度的空功能模块。
 *
 * <p>它只响应没有副作用的 {@code ping} 动作，不读取参数、不访问文件、
 * 不调用网络或系统能力。保留该模块可以在后续新增真实模块时持续验证
 * 注册表、调度层和前端占位视图是否仍能正常工作。</p>
 */
@Component
public class SampleTestModule implements PlatformModule, ModuleHandler {

    private static final String MODULE_ID = "sample-empty";
    private static final String PING_ACTION = "ping";

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(
                MODULE_ID,
                "空功能试验台",
                "仅验证模块注册、发现与调度接口，不执行任何真实操作。",
                List.of(PING_ACTION),
                999
        );
    }

    @Override
    public boolean supports(String action) {
        return PING_ACTION.equals(action);
    }

    @Override
    public ModuleCommandResult handle(ModuleCommand command) {
        return new ModuleCommandResult(
                ModuleCommandResult.Status.COMPLETED,
                "空功能模块已响应测试命令。",
                Map.of("sideEffects", false)
        );
    }
}
