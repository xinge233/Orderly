package com.orderly.platform.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证空模块只接受显式声明的无副作用动作。
 */
class ModuleDispatchServiceTest {

    private final ModuleDispatchService service = new ModuleDispatchService(
            new ModuleRegistry(List.of(new SampleTestModule()))
    );

    @Test
    void shouldDispatchDeclaredPingAction() {
        ModuleCommandResult result = service.dispatch(
                new ModuleCommand("sample-empty", "ping", Map.of())
        );

        assertThat(result.status()).isEqualTo(ModuleCommandResult.Status.COMPLETED);
        assertThat(result.output()).containsEntry("sideEffects", false);
    }

    @Test
    void shouldRejectUnknownActionBeforeHandling() {
        ModuleCommandResult result = service.dispatch(
                new ModuleCommand("sample-empty", "delete-all", Map.of())
        );

        assertThat(result.status()).isEqualTo(ModuleCommandResult.Status.REJECTED);
        assertThat(result.message()).contains("不支持动作");
    }

    @Test
    void shouldRejectUnknownModule() {
        ModuleCommandResult result = service.dispatch(
                new ModuleCommand("missing", "ping", Map.of())
        );

        assertThat(result.status()).isEqualTo(ModuleCommandResult.Status.REJECTED);
        assertThat(result.message()).contains("未注册");
    }
}
