package com.orderly.kernel;

/**
 * 工作区中单个文件或目录的只读摘要。
 *
 * <p>实例由宿主运行时生成，供模块查询结果返回前端。{@code path} 始终使用以
 * 工作区根目录为基准的相对路径，不携带宿主机绝对路径。</p>
 *
 * @param name 名称
 * @param path 工作区相对路径
 * @param directory 是否为目录
 * @param size 文件字节数；目录值取决于底层文件系统
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
