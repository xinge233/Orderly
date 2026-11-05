package com.orderly.platform.module;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void shouldRejectDuplicateModuleIds() {
        PlatformModule duplicate = new PlatformModule() {
            @Override
            public ModuleDescriptor descriptor() {
                return new ModuleDescriptor(
                        "sample-empty",
                        "重复模块",
                        "用于重复 id 测试",
                        List.of(),
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
                List.of(),
                order
        );
    }
}
