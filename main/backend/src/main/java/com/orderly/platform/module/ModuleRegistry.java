package com.orderly.platform.module;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模块发现与唯一性治理中心。
 *
 * <p>Spring 将实现 {@link PlatformModule} 的 Bean 自动注入此处。注册表在启动时
 * 做唯一性和基础元数据校验，使重复模块或空描述尽早失败，而不是在运行中产生歧义。</p>
 */
@Component
public class ModuleRegistry {

    private final Map<String, PlatformModule> modulesById;

    /**
     * 构建只读模块注册表。
     *
     * @param modules Spring 发现的所有平台模块
     */
    public ModuleRegistry(List<PlatformModule> modules) {
        Map<String, PlatformModule> registered = new LinkedHashMap<>();

        modules.stream()
                .sorted(Comparator
                        .comparingInt((PlatformModule module) -> module.descriptor().order())
                        .thenComparing(module -> module.descriptor().id()))
                .forEach(module -> {
                    ModuleDescriptor descriptor = module.descriptor();
                    if (registered.putIfAbsent(descriptor.id(), module) != null) {
                        throw new IllegalStateException(
                                "检测到重复模块 id：" + descriptor.id()
                        );
                    }
                });

        // Map.copyOf 不承诺保留遍历顺序；这里显式保留已排序的 LinkedHashMap。
        this.modulesById = Collections.unmodifiableMap(registered);
    }

    /**
     * 按稳定顺序返回所有模块的公开描述。
     *
     * @return 不可修改的模块目录
     */
    public List<ModuleDescriptor> listDescriptors() {
        return modulesById.values().stream()
                .map(PlatformModule::descriptor)
                .toList();
    }

    /**
     * 按 id 查找模块实例，供受控调度层使用。
     *
     * @param moduleId 模块标识
     * @return 找到时返回模块实例
     */
    public Optional<PlatformModule> find(String moduleId) {
        return Optional.ofNullable(modulesById.get(moduleId));
    }
}
