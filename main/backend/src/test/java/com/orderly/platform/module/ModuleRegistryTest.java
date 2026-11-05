package com.orderly.platform.module;

import com.orderly.platform.execution.ExecutionPermit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证模块注册表的发现顺序与重复 id 防护。
 */
class ModuleRegistryTest {

    @Test
    void shouldExposeTheEmptyModuleInStableOrder() {
        ModuleRegistry registry = new ModuleRegistry(List.of(new SampleTestModule()));

        assertThat(registry.listDescriptors())
                .extracting(ModuleDescriptor::id)
                .containsExactly("sample-empty");
    }

    @Test
    void shouldIndexTheSingleDeclaredAction() {
        ModuleRegistry registry = new ModuleRegistry(List.of(new SampleTestModule()));

        assertThat(registry.findAction("sample-empty", "ping"))
                .isPresent()
                .get()
                .extracting(ModuleActionDefinition::riskLevel)
                .isEqualTo(ActionRiskLevel.READ_ONLY);
        assertThat(registry.findAction("sample-empty", "unsupported")).isEmpty();
    }

    @Test
    void shouldRejectDuplicateModuleIds() {
        PlatformModule duplicate = new PlatformModule() {
            @Override
            public ModuleDescriptor descriptor() {
                return new ModuleDescriptor(
                        "sample-empty",
                        "重复模块",
                        "用于重复 id 测试",
                        1
                );
            }
        };

        assertThatThrownBy(() -> new ModuleRegistry(
                List.of(new SampleTestModule(), duplicate)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复模块 id");
    }

    @Test
    void shouldKeepDescriptorsInDeclaredOrder() {
        PlatformModule laterModule = moduleWithOrder("later", 20);
        PlatformModule earlierModule = moduleWithOrder("earlier", 10);

        ModuleRegistry registry = new ModuleRegistry(List.of(laterModule, earlierModule));

        assertThat(registry.listDescriptors())
                .extracting(ModuleDescriptor::id)
                .containsExactly("earlier", "later");
    }

    @Test
    void shouldFreezeDescriptorAndActionCatalogsAtStartup() {
        AtomicInteger actionReads = new AtomicInteger();
        PlatformModule dynamicModule = new PlatformModule() {
            @Override
            public ModuleDescriptor descriptor() {
                return new ModuleDescriptor("stable", "稳定模块", "注册快照", 1);
            }
        };
        ModuleHandler dynamicHandler = new ModuleHandler() {
            @Override
            public List<ModuleActionDefinition> actions() {
                actionReads.incrementAndGet();
                return List.of(new ModuleActionDefinition(
                        "ping",
                        "响应",
                        "无副作用",
                        ParameterSchema.empty(),
                        ActionRiskLevel.READ_ONLY,
                        false,
                        true,
                        false,
                        WorkspaceAccess.NONE
                ));
            }

            @Override
            public ModuleCommandResult execute(
                    ModuleCommand command,
                    ExecutionPermit permit
            ) {
                return ModuleCommandResult.completed("ok");
            }
        };

        ModuleRegistry registry = new ModuleRegistry(List.of(
                new CompositeTestModule(dynamicModule, dynamicHandler)
        ));
        registry.listDescriptors();
        registry.listActions("stable");
        registry.listActions("stable");

        assertThat(actionReads).hasValue(1);
        assertThat(registry.findAction("stable", "ping")).isPresent();
    }

    /**
     * 创建只用于排序测试的模块。
     *
     * @param id 模块标识
     * @param order 声明顺序
     * @return 测试模块
     */
    private PlatformModule moduleWithOrder(String id, int order) {
        return () -> new ModuleDescriptor(
                id,
                id,
                "排序测试模块",
                order
        );
    }

    /**
     * 同时暴露模块描述和动作表的测试组合。
     */
    private record CompositeTestModule(
            PlatformModule descriptorSource,
            ModuleHandler handler
    ) implements PlatformModule, ModuleHandler {
        @Override
        public ModuleDescriptor descriptor() {
            return descriptorSource.descriptor();
        }

        @Override
        public List<ModuleActionDefinition> actions() {
            return handler.actions();
        }

        @Override
        public ModuleCommandResult execute(
                ModuleCommand command,
                ExecutionPermit permit
        ) {
            return handler.execute(command, permit);
        }
    }
}
