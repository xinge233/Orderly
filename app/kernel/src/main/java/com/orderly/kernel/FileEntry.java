package com.orderly.kernel;

/**
 * 模块可安全返回给宿主的文件摘要。
 *
 * @param name 名称
 * @param path 工作区相对路径
 * @param directory 是否为目录
 * @param size 字节数
 * @param modifiedAt ISO-8601 修改时间
 */
public record FileEntry(
        String name,
        String path,
        boolean directory,
        long size,
        String modifiedAt
) {
}
