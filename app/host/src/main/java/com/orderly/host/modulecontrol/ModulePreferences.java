package com.orderly.host.modulecontrol;

import java.util.List;
import java.util.Map;

/**
 * 前端模块偏好接口的稳定响应。
 *
 * <p>包含侧边栏顺序、管理页顺序和各业务模块已合并的有效设置。构造时只做顶层不可变
 * 复制，设置值仍应是 setting 定义允许的 JSON 基础类型。</p>
 */
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
