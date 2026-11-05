package com.orderly.platform.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证动作声明的安全约束。
 */
class ModuleActionDefinitionTest {

    @Test
    void shouldRejectWriteActionWithoutConfirmation() {
        assertThatThrownBy(() -> new ModuleActionDefinition(
                "move",
                "移动",
                "测试动作",
                ParameterSchema.empty(),
                ActionRiskLevel.WRITE,
                false,
                true,
                true,
                WorkspaceAccess.WRITE
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须要求用户确认");
    }

    @Test
    void shouldRejectReadOnlyActionWithWriteScope() {
        assertThatThrownBy(() -> new ModuleActionDefinition(
                "inspect",
                "检查",
                "测试动作",
                ParameterSchema.empty(),
                ActionRiskLevel.READ_ONLY,
                false,
                true,
                false,
                WorkspaceAccess.WRITE
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读动作");
    }

    @Test
    void shouldValidateRequiredAndUnknownParameters() {
        ParameterSchema schema = new ParameterSchema(java.util.List.of(
                new ActionParameterDefinition(
                        "message",
                        "测试消息",
                        ActionParameterType.STRING,
                        true
                )
        ));

        assertThat(schema.validate(java.util.Map.of()))
                .contains("缺少必填参数：message");
        assertThat(schema.validate(java.util.Map.of(
                "message", "hello",
                "extra", true
        )))
                .contains("未知参数：extra");
        assertThat(schema.validate(java.util.Map.of("message", "hello")))
                .isEmpty();
    }
}
