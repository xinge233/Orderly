package com.orderly.host.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 系统模块元数据；业务模块不声明该字段。 */
public record ModuleSystemMetadata(
        String role,
        @JsonProperty("protected") boolean protectedModule
) {
    public static final String CONTROL_ROLE = "MODULE_CONTROL";

    public ModuleSystemMetadata {
        if (!CONTROL_ROLE.equals(role)) {
            throw new IllegalArgumentException("不支持的系统模块角色：" + role);
        }
    }
}
