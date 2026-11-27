package com.orderly.kernel;

/**
 * 文件写入结果。
 *
 * <p>{@code backup} 仅当覆盖已有文件且宿主成功把旧对象移入项目回收站时存在。
 * 回滚使用该条目标识恢复旧内容，模块不得据此自行判定授权。</p>
 */
public record FileWriteResult(
        String relativePath,
        long size,
        TrashEntry backup
) {
}
