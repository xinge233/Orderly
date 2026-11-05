package com.orderly.platform.api;

import com.orderly.platform.module.ActionParameterDefinition;
import com.orderly.platform.module.ModuleActionDefinition;
import com.orderly.platform.module.ModuleDescriptor;
import com.orderly.platform.module.ModuleRegistry;
import com.orderly.platform.module.ParameterSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部模块模型到公共 API DTO 的唯一映射器。
 *
 * <p>控制器不直接返回内部 record，公共字段只能在这里显式新增，
 * 从架构上阻止内部重构意外改变 API。</p>
 */
@Component
public class ModuleCatalogMapper {

    /** 当前公开模块目录契约版本。 */
    public static final String CONTRACT_VERSION = "1.0";

    private final ModuleRegistry registry;

    /**
     * @param registry 平台模块注册表
     */
    public ModuleCatalogMapper(ModuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * 创建完整模块目录响应。
     *
     * @return 版本化公共响应
     */
    public ModuleCatalogResponse toResponse() {
        List<ModuleSummaryResponse> modules = registry.listDescriptors().stream()
                .map(this::toModule)
                .toList();
        return new ModuleCatalogResponse(CONTRACT_VERSION, "modular-skeleton", modules);
    }

    /**
     * 映射单个模块。
     *
     * @param descriptor 内部模块描述
     * @return 公共模块摘要
     */
    private ModuleSummaryResponse toModule(ModuleDescriptor descriptor) {
        List<ModuleActionResponse> actions = registry.listActions(descriptor.id()).stream()
                .map(this::toAction)
                .toList();

        return new ModuleSummaryResponse(
                descriptor.id(),
                descriptor.displayName(),
                descriptor.description(),
                descriptor.order(),
                actions
        );
    }

    /**
     * 映射单个动作。
     *
     * @param action 内部动作定义
     * @return 公共动作响应
     */
    private ModuleActionResponse toAction(ModuleActionDefinition action) {
        return new ModuleActionResponse(
                action.id(),
                action.displayName(),
                action.description(),
                toParameterSchema(action.parameterSchema()),
                action.riskLevel().name(),
                action.confirmationRequired(),
                action.idempotent(),
                action.rollbackSupported(),
                action.workspaceAccess().name()
        );
    }

    /**
     * 映射参数 schema，并显式禁止额外字段。
     *
     * @param schema 内部参数 schema
     * @return 公共参数 schema
     */
    private ParameterSchemaResponse toParameterSchema(ParameterSchema schema) {
        List<ParameterPropertyResponse> properties = schema.properties().stream()
                .map(this::toProperty)
                .toList();
        List<String> required = schema.properties().stream()
                .filter(ActionParameterDefinition::required)
                .map(ActionParameterDefinition::name)
                .toList();

        return new ParameterSchemaResponse(
                "object",
                properties,
                required,
                false
        );
    }

    /**
     * 映射单个参数属性。
     *
     * @param property 内部属性
     * @return 公共属性
     */
    private ParameterPropertyResponse toProperty(ActionParameterDefinition property) {
        return new ParameterPropertyResponse(
                property.name(),
                property.description(),
                property.type().name(),
                property.required()
        );
    }
}
