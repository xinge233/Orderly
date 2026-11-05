package com.orderly.platform.module;

import com.orderly.platform.execution.ExecutionPermit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用于验证模块注册与调度的空功能模块。
 *
 * <p>它只声明没有副作用的 {@code ping} 动作，不读取参数、不访问文件、
 * 不调用网络或系统能力。保留该模块可以在后续新增真实模块时持续验证
 * 注册表、调度层和前端占位视图是否仍能正常工作。</p>
 */
@Component
public class SampleTestModule implements PlatformModule, ModuleHandler {

    private static final String MODULE_ID = "sample-empty";
    private static final String PING_ACTION = "ping";
    private static final List<ModuleActionDefinition> ACTIONS = List.of(
            new ModuleActionDefinition(
                    PING_ACTION,
                    "空模块响应测试",
                    "验证中央执行管线与空功能模块，不产生任何副作用。",
                    ParameterSchema.empty(),
                    ActionRiskLevel.READ_ONLY,
                    false,
                    true,
                    false,
                    WorkspaceAccess.NONE
            )
    );

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(
                MODULE_ID,
                "空功能试验台",
                "仅验证模块注册、发现与调度接口，不执行任何真实操作。",
                999
        );
    }

    @Override
    public List<ModuleActionDefinition> actions() {
        return ACTIONS;
    }

    @Override
    public ModuleCommandResult execute(ModuleCommand command, ExecutionPermit permit) {
        // 许可由中央管线签发并与本次模块/动作绑定，防止执行入口被直接复用。
        permit.requireFor(MODULE_ID, PING_ACTION);
        return new ModuleCommandResult(
                ModuleCommandResult.Status.COMPLETED,
                "空功能模块已响应测试命令。",
                Map.of("sideEffects", false)
        );
    }
}
