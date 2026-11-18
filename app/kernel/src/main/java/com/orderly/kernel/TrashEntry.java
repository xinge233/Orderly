package com.orderly.kernel;

import java.util.Map;

/**
 * 项目回收站中的一个可恢复条目。
 *
 * <p>条目只描述对象的原位置和回收站内存储位置，不包含宿主机绝对路径。恢复行为由
 * 宿主 {@code ModuleRuntime} 执行；本对象本身不移动文件。</p>
 *
 * @param id 条目 ID
 * @param originalPath 原工作区相对路径
 * @param storedPath 回收站相对存储路径
 * @param name 原始名称
 * @param deletedAt 删除时间
 */
public record TrashEntry(
        String id,
        String originalPath,
        String storedPath,
        String name,
        String deletedAt
) {
    /**
     * 转换为回收站清单使用的普通映射。
     *
     * <p>方法只创建可 JSON 序列化的视图，不修改条目或文件系统。缺失字段不会在此处
     * 补齐，持久化调用方仍需保证输入完整。</p>
     */
    public Map<String, Object> asMap() {
        return Map.of(
                "id", id,
                "originalPath", originalPath,
                "storedPath", storedPath,
                "name", name,
                "deletedAt", deletedAt
        );
    }
}
