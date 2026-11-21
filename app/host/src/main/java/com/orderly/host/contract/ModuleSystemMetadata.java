package com.orderly.host.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 系统模块元数据；业务模块不声明该字段。 */
public record ModuleSystemMetadata(
        String role,
        @JsonProperty("protected") boolean protectedModule,
        List<String> capabilities
) {
    public static final String CONTROL_ROLE = "MODULE_CONTROL";
    public static final String SERVICE_ROLE = "SYSTEM_SERVICE";
    public static final String CAPABILITY_MODULE_GOVERNANCE = "MODULE_GOVERNANCE";

    private static final Set<String> SUPPORTED_ROLES =
            Set.of(CONTROL_ROLE, SERVICE_ROLE);
    private static final Set<String> SUPPORTED_CAPABILITIES =
            Set.of(CAPABILITY_MODULE_GOVERNANCE);

    public ModuleSystemMetadata {
        if (!SUPPORTED_ROLES.contains(role)) {
            throw new IllegalArgumentException("不支持的系统模块角色：" + role);
        }
        if (!protectedModule) {
            throw new IllegalArgumentException("模块 system.protected 必须为 true");
        }
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        if (new HashSet<>(capabilities).size() != capabilities.size()) {
            throw new IllegalArgumentException("系统模块 capabilities 不能重复");
        }
        if (!SUPPORTED_CAPABILITIES.containsAll(capabilities)) {
            throw new IllegalArgumentException("不支持的系统模块能力：" + capabilities);
        }
        boolean governance = capabilities.contains(CAPABILITY_MODULE_GOVERNANCE);
        if (CONTROL_ROLE.equals(role) && !governance) {
            throw new IllegalArgumentException(
                    "MODULE_CONTROL 必须声明 MODULE_GOVERNANCE 能力"
            );
        }
        if (SERVICE_ROLE.equals(role) && governance) {
            throw new IllegalArgumentException(
                    "SYSTEM_SERVICE 不能声明 MODULE_GOVERNANCE 能力"
            );
        }
    }
}
