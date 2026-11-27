package com.orderly.host.runtime;

import com.orderly.kernel.AuthorizedFileRoot;

import java.nio.file.Path;

/**
 * 宿主内部的授权根存储记录。
 *
 * <p>绝对路径只在此记录中保存；交给模块的 {@link AuthorizedFileRoot} 会移除路径。</p>
 */
public record FileRootRegistration(
        String id,
        String label,
        String path,
        boolean read,
        boolean write,
        boolean delete
) {
    Path rootPath() {
        return Path.of(path).toAbsolutePath().normalize();
    }

    AuthorizedFileRoot descriptor() {
        return new AuthorizedFileRoot(id, label, read, write, delete);
    }
}
