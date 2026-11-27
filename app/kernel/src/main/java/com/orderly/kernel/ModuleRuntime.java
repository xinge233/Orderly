package com.orderly.kernel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 宿主提供给业务模块的唯一受控运行时能力。
 *
 * <p>模块不得自行打开数据库、直接访问任意文件系统路径或实现物理删除。工作区
 * 解析、移动、回收站和共享集合读写均由宿主实现。该 SPI 是应用行为边界，不是
 * 恶意 JAR 的 JVM 安全沙箱；外部模块仍被视为本地受信任代码。</p>
 */
public interface ModuleRuntime {

    /** 返回面向用户的工作区显示名称；调用无副作用，不暴露宿主机绝对路径。 */
    String workspaceLabel();

    /**
     * 返回用户显式授权的本地文件根目录快照。
     *
     * <p>返回值按注册顺序稳定排列，只包含根标识、显示名称和能力位，不包含宿主机
     * 绝对路径；模块只能使用返回的 id 调用宿主解析方法，不得自行拼接根路径。</p>
     */
    List<AuthorizedFileRoot> listFileRoots();

    /**
     * 将授权根内相对路径解析为宿主受控绝对路径。
     *
     * <p>拒绝绝对路径、越界路径和经过现有链接或重解析点的逃逸。目标可以尚不存在，
     * 但最近的现有祖先必须真实位于授权根内。</p>
     */
    Path resolveAuthorizedPath(String rootId, String relativePath);

    /** 列出授权根目录的直接子项；结果路径相对于该根。 */
    List<FileEntry> listAuthorizedDirectory(Path directory);

    /**
     * 凭宿主签发的短时令牌读取文件正文。
     *
     * <p>令牌只绑定宿主内存中的授权根、相对路径和用途，模块不接收文件路径。结果不含
     * 绝对路径；令牌缺失、过期或越界时拒绝读取。</p>
     */
    FileReadResult readGrantedText(String token, int offset, int maxChars);

    /**
     * 使用操作系统默认应用打开授权根内普通文件。
     *
     * <p>该能力只读路径内容，但会启动外部程序；是否允许由宿主审计并确认。外部应用
     * 后续对文件的操作不由 Orderly 担保。</p>
     */
    void openFile(Path file);

    /**
     * 在授权根内新建 UTF-8 文本文件，并自动创建缺失父目录；目标已存在时拒绝。
     */
    Path createAuthorizedTextFile(Path file, String content);

    /**
     * 在授权根内写入 UTF-8 文本文件。
     *
     * <p>覆盖已有文件前由宿主把旧对象移入项目回收站并返回备份条目；目标已存在且
     * {@code overwrite=false} 时拒绝。成功写入通过同目录临时文件替换。</p>
     */
    FileWriteResult writeAuthorizedTextFile(
            Path file,
            String content,
            boolean overwrite,
            String planId,
            String stepId
    );

    /**
     * 把宿主已暂存的普通二进制文件写入授权根。
     *
     * <p>覆盖已有目标时先把旧对象移入项目回收站。暂存文件必须位于宿主工作区，
     * 目标不得位于系统保护目录。</p>
     */
    FileWriteResult writeAuthorizedBinaryFile(
            Path stagedFile,
            Path target,
            boolean overwrite,
            String planId,
            String stepId
    );

    /**
     * 将工作区相对路径规范化为宿主受控路径。
     *
     * <p>拒绝绝对路径、规范化后越出工作区的路径，以及经过现有符号链接或重解析点
     * 逃逸的祖先路径。方法只解析和校验，不创建目标；返回路径可以尚不存在。路径
     * 格式非法或运行时尚未初始化时抛出异常。</p>
     */
    Path resolveWorkspacePath(String relativePath);

    /**
     * 判断工作区路径当前是否存在。
     *
     * <p>调用方应先通过 {@link #resolveWorkspacePath(String)} 取得路径。检查不跟随
     * 末尾符号链接，不创建、移动或读取目标内容；I/O 级细粒度错误可能表现为不存在。</p>
     */
    boolean exists(Path path);

    /**
     * 列出工作区目录的直接子项。
     *
     * <p>前置条件为路径位于工作区内且当前指向目录。结果按目录优先、名称升序稳定
     * 排列，每个条目使用工作区相对路径。目标不是目录或读取失败时抛出异常，调用
     * 不产生文件副作用。</p>
     */
    List<FileEntry> listDirectory(Path directory);

    /**
     * 创建一个目录及其缺失父目录。
     *
     * <p>目标已存在时拒绝执行，不覆盖文件或目录。操作具有持久化副作用；创建多个
     * 父目录时若后续失败，文件系统可能保留已经创建的前缀目录。</p>
     */
    Path createDirectory(Path directory);

    /**
     * 创建 UTF-8 文本文件，并按需创建缺失父目录。
     *
     * <p>实现先检查目标是否存在，已存在时拒绝覆盖。写入会持久化文件内容；当前接口
     * 不声明跨进程原子排他，并发创建仍须以实际异常和文件状态为准。</p>
     */
    Path createTextFile(Path file, String content);

    /**
     * 读取工作区内普通 UTF-8 文本文件。
     *
     * <p>目标缺失、不是普通文件或 I/O 失败时抛出异常。调用只读，不创建缓存文件，
     * 也不改变文件访问之外的业务状态。</p>
     */
    String readTextFile(Path file);

    /**
     * 在工作区内部移动一个文件或目录。
     *
     * <p>源必须存在且不能是工作区根；目标已存在时拒绝覆盖。该操作不是物理删除，
     * 但会立即改变两侧路径。跨文件系统或底层移动失败时抛出异常，调用方不得假定
     * 原子回退。</p>
     */
    void move(Path source, Path target);

    /**
     * 将工作区对象移入项目回收站，并登记回收站清单。
     *
     * <p>{@code planId}/{@code stepId} 只用于生成安全的分区目录。目标重名时会追加
     * 唯一前缀，避免覆盖既有回收站对象。文件移动与清单写入不是单一事务；清单写入
     * 失败时对象可能已经位于回收站而调用仍抛异常，失败后须检查事件和磁盘状态。
     * 该操作不会物理删除内容，也不是可重复调用的幂等操作。</p>
     */
    TrashEntry moveToTrash(Path source, String planId, String stepId);

    /**
     * 宿主撤销写入、创建或恢复动作时把对象移入项目回收站。
     *
     * <p>该入口只服务于宿主统一回滚和覆盖前备份，不要求用户授予直接删除能力，但仍
     * 校验路径属于已授权的可读根、拒绝系统保护目录和受控根本身。业务动作不得用它
     * 替代用户显式删除。</p>
     */
    TrashEntry moveToTrashForRecovery(Path source, String planId, String stepId);

    /**
     * 按回收站条目 ID 将对象恢复到原工作区相对位置。
     *
     * <p>条目和存储路径必须有效，原位置必须不存在。移动成功后删除清单条目；若恢复
     * 后的清单写入失败，可能出现对象已恢复但清单仍暂时过期的状态。重复恢复同一 ID
     * 会因条目消失或原位置已占用而失败。</p>
     */
    Path restoreFromTrash(String trashId);

    /** 返回按删除时间倒序排列的回收站条目快照；调用只读，不修复损坏清单。 */
    List<TrashEntry> listTrash();

    /**
     * 读取模块自有 JSON 集合。
     *
     * <p>集合名必须满足宿主命名规则。尚未创建的文件返回空列表；已有文件必须是
     * 对象数组，否则解析失败并抛出异常。返回值为每一层的顶层不可变副本，不建立
     * 缓存，也不检查集合 owner（当前由模块命名和审查约束保证独占性）。</p>
     */
    List<Map<String, Object>> readItems(String collection);

    /** 判断集合状态文件是否已创建，即使 JSON 数组为空；调用不校验文件内容。 */
    boolean hasItems(String collection);

    /**
     * 将模块自有集合整体替换为给定 JSON 对象数组。
     *
     * <p>实现先写同目录临时文件，再优先原子替换正式文件；文件系统不支持原子移动时
     * 回退为普通替换。调用会持久化并覆盖该集合的旧内容，写入失败时抛出异常。</p>
     */
    void writeItems(String collection, List<Map<String, Object>> items);

    /** 生成一个随机 UUID 字符串；仅在内存中生成，不持久化也不预留名称。 */
    String newId();

    /**
     * 将工作区或回收站内的受控路径转换为前端可用的相对字符串。
     *
     * <p>工作区路径不带前缀，回收站路径以 {@code trash/} 开头。路径必须位于这两个
     * 根目录内；方法只做规范化与词法归属检查，不读取目标。</p>
     */
    String relativePath(Path path);
}
