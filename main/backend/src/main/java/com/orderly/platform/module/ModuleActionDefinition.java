package com.orderly.platform.module;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模块动作的单一可信声明。
 *
 * <p>动作是否可执行不再依赖模块自行实现的 {@code supports()}，而是由注册表
 * 根据这份定义建立索引。执行管线只调用这里已经登记的动作，从根源上消除
 * “能力列表”和“实际支持集合”漂移的可能。</p>
 *
 * @param id 模块内唯一的动作 ID
 * @param displayName 面向用户的动作名称
 * @param description 动作边界说明
 * @param parameterSchema 受控参数描述
 * @param riskLevel 风险等级
 * @param confirmationRequired 是否需要服务端计划确认
 * @param idempotent 重复调用是否具有相同效果
 * @param rollbackSupported 是否声明支持回滚
 * @param workspaceAccess 所需工作区访问范围
 */
public record ModuleActionDefinition(
        String id,
        String displayName,
        String description,
        ParameterSchema parameterSchema,
        ActionRiskLevel riskLevel,
        boolean confirmationRequired,
        boolean idempotent,
        boolean rollbackSupported,
        WorkspaceAccess workspaceAccess
) {
    private static final Pattern ACTION_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    public ModuleActionDefinition {
        id = Objects.requireNonNullElse(id, "").trim();
        displayName = Objects.requireNonNullElse(displayName, "").trim();
        description = Objects.requireNonNullElse(description, "").trim();
        parameterSchema = Objects.requireNonNull(parameterSchema, "参数 schema 不能为空");
        riskLevel = Objects.requireNonNull(riskLevel, "风险等级不能为空");
        workspaceAccess = Objects.requireNonNull(workspaceAccess, "工作区访问范围不能为空");

        if (!ACTION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法动作 ID：" + id);
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("动作显示名称不能为空");
        }
        if (riskLevel != ActionRiskLevel.READ_ONLY && !confirmationRequired) {
            throw new IllegalArgumentException(
                    "写入或破坏性动作必须要求用户确认：" + id
            );
        }
        if (riskLevel == ActionRiskLevel.READ_ONLY
                && workspaceAccess == WorkspaceAccess.WRITE) {
            throw new IllegalArgumentException("只读动作不能声明工作区写权限：" + id);
        }
    }
}
