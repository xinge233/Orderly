package com.orderly.platform.module;

import java.util.Objects;

/**
 * 模块对外的稳定描述。
 *
 * <p>该结构只包含界面发现和模块编排所需的元数据，不携带任何业务执行结果。
 * 模块可以新增能力名称，但不能借助描述结构直接要求其他模块执行动作。</p>
 *
 * @param id 稳定且唯一的模块标识，建议使用小写短横线格式
 * @param displayName 面向用户的模块名称
 * @param description 简短的能力边界说明
 * 动作能力不在此结构中声明，统一由 {@link ModuleHandler#actions()} 提供。
 *
 * @param order 导航/发现顺序，数值越小越靠前
 */
public record ModuleDescriptor(
        String id,
        String displayName,
        String description,
        int order
) {
    public ModuleDescriptor {
        // 在结构入口统一校验，避免非法模块元数据扩散到注册表和接口层。
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("模块 id 不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("模块显示名称不能为空");
        }
        description = Objects.requireNonNullElse(description, "").trim();
    }
}
