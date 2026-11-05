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
    private final Map<String, ModuleDescriptor> descriptorsById;
    private final Map<String, ModuleActionDefinition> actionsByKey;
    private final Map<String, List<ModuleActionDefinition>> actionsByModule;

    /**
     * 构建只读模块注册表。
     *
     * @param modules Spring 发现的所有平台模块
     */
    public ModuleRegistry(List<PlatformModule> modules) {
        Map<String, PlatformModule> registered = new LinkedHashMap<>();
        Map<String, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, ModuleActionDefinition> indexedActions = new LinkedHashMap<>();
        Map<String, List<ModuleActionDefinition>> moduleActions = new LinkedHashMap<>();

        modules.stream()
                .map(module -> new ModuleCandidate(module, module.descriptor()))
                .sorted(Comparator
                        .comparingInt((ModuleCandidate candidate) -> candidate.descriptor().order())
                        .thenComparing(candidate -> candidate.descriptor().id()))
                .forEach(candidate -> {
                    PlatformModule module = candidate.module();
                    ModuleDescriptor descriptor = candidate.descriptor();
                    if (registered.putIfAbsent(descriptor.id(), module) != null) {
                        throw new IllegalStateException(
                                "检测到重复模块 id：" + descriptor.id()
                        );
                    }
                    descriptors.put(descriptor.id(), descriptor);

                    if (module instanceof ModuleHandler handler) {
                        List<ModuleActionDefinition> actions = validateAndIndexActions(
                                descriptor.id(),
                                handler.actions(),
                                indexedActions
                        );
                        moduleActions.put(descriptor.id(), actions);
                    } else {
                        moduleActions.put(descriptor.id(), List.of());
                    }
                });

        // Map.copyOf 不承诺保留遍历顺序；这里显式保留已排序的 LinkedHashMap。
        this.modulesById = Collections.unmodifiableMap(registered);
        this.descriptorsById = Collections.unmodifiableMap(descriptors);
        this.actionsByKey = Collections.unmodifiableMap(indexedActions);
        this.actionsByModule = Collections.unmodifiableMap(moduleActions);
    }

    /**
     * 按稳定顺序返回所有模块的公开描述。
     *
     * @return 不可修改的模块目录
     */
    public List<ModuleDescriptor> listDescriptors() {
        return List.copyOf(descriptorsById.values());
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

    /**
     * 按模块和动作查找唯一可信的动作定义。
     *
     * @param moduleId 模块标识
     * @param actionId 动作标识
     * @return 找到时返回动作定义
     */
    public Optional<ModuleActionDefinition> findAction(String moduleId, String actionId) {
        return Optional.ofNullable(actionsByKey.get(actionKey(moduleId, actionId)));
    }

    /**
     * 返回模块在启动时冻结的动作定义快照。
     *
     * @param moduleId 模块标识
     * @return 不可修改动作列表；未知模块返回空列表
     */
    public List<ModuleActionDefinition> listActions(String moduleId) {
        return actionsByModule.getOrDefault(moduleId, List.of());
    }

    /**
     * 在启动阶段校验并索引模块动作。
     *
     * <p>动作 ID 重复或危险动作未声明确认时直接终止应用启动，避免错误配置
     * 进入运行阶段。</p>
     *
     * @param moduleId 模块标识
     * @param actions 注册时声明的动作快照
     * @param indexedActions 待构建的动作索引
     */
    private List<ModuleActionDefinition> validateAndIndexActions(
            String moduleId,
            List<ModuleActionDefinition> actions,
            Map<String, ModuleActionDefinition> indexedActions
    ) {
        List<ModuleActionDefinition> snapshot = List.copyOf(actions);
        for (ModuleActionDefinition action : snapshot) {
            String key = actionKey(moduleId, action.id());
            if (indexedActions.putIfAbsent(key, action) != null) {
                throw new IllegalStateException(
                        "模块 " + moduleId + " 声明了重复动作 id：" + action.id()
                );
            }
        }
        return snapshot;
    }

    /**
     * 构造不会与分隔符混淆的动作索引键。
     *
     * @param moduleId 模块标识
     * @param actionId 动作标识
     * @return 动作索引键
     */
    private String actionKey(String moduleId, String actionId) {
        return moduleId + "\u0000" + actionId;
    }

    /**
     * 保存启动时模块与描述的对应关系，杜绝后续调用返回不同动态值。
     *
     * @param module 模块实例
     * @param descriptor 注册时描述快照
     */
    private record ModuleCandidate(
            PlatformModule module,
            ModuleDescriptor descriptor
    ) {
    }
}
