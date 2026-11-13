package com.orderly.host.modulecontrol;

import java.util.List;
import java.util.Map;

/** 前端模块偏好接口的稳定响应。 */
public record ModulePreferences(
        List<String> sidebarOrder,
        List<String> managementOrder,
        Map<String, Map<String, Object>> moduleSettings
) {
    public ModulePreferences {
        sidebarOrder = List.copyOf(sidebarOrder);
        managementOrder = List.copyOf(managementOrder);
        moduleSettings = Map.copyOf(moduleSettings);
    }
}
