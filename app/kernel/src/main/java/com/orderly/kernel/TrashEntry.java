package com.orderly.kernel;

import java.util.Map;

/**
 * 回收站条目。路径均为工作区或应用数据目录内的相对值。
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
    /** 转换为可持久化的普通映射。 */
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
