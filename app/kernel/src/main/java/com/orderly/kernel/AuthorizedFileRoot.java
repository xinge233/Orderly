package com.orderly.kernel;

/**
 * 用户显式授权的本地文件根目录。
 *
 * <p>绝对路径只保存在宿主注册表中；模块只收到不含路径的根标识、名称和能力。
 * 模块按 {@code id} 和根内相对路径访问，不自行拼接或判断授权。能力位由宿主
 * 注册表持久化，模块不得自行提升。</p>
 *
 * @param id 稳定根标识
 * @param label 用户可读名称
 * @param read 是否允许只读访问
 * @param write 是否允许创建、覆盖和移动
 * @param delete 是否允许移入项目回收站
 */
public record AuthorizedFileRoot(
        String id,
        String label,
        boolean read,
        boolean write,
        boolean delete
) {
}
