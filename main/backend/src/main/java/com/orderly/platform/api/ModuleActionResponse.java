package com.orderly.platform.api;

/**
 * 模块动作的版本化公共契约。
 *
 * @param id 动作标识
 * @param displayName 面向用户的名称
 * @param description 动作边界说明
 * @param parameterSchema 参数 schema
 * @param riskLevel 风险等级
 * @param confirmationRequired 是否需要计划确认
 * @param idempotent 是否幂等
 * @param rollbackSupported 是否支持回滚
 * @param workspaceAccess 工作区访问范围
 */
public record ModuleActionResponse(
        String id,
        String displayName,
        String description,
        ParameterSchemaResponse parameterSchema,
        String riskLevel,
        boolean confirmationRequired,
        boolean idempotent,
        boolean rollbackSupported,
        String workspaceAccess
) {
}
