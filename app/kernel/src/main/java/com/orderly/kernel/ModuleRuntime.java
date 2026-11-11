package com.orderly.kernel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 宿主提供给模块的受控能力。
 *
 * <p>模块不得自行打开数据库或访问任意文件系统路径。所有工作区解析、移动、
 * 回收站和共享状态读写都由宿主实现并在调用前执行边界检查。</p>
 */
public interface ModuleRuntime {

    /** 返回当前工作区显示名称，不暴露宿主机绝对路径。 */
    String workspaceLabel();

    /** 将工作区相对路径解析为受边界保护的真实路径。 */
    Path resolveWorkspacePath(String relativePath);

    /** 判断受保护路径当前是否存在。 */
    boolean exists(Path path);

    /** 返回目录列表，路径以工作区根目录为基准。 */
    List<FileEntry> listDirectory(Path directory);

    /** 创建一个目录；已存在的文件或目录不会被覆盖。 */
    Path createDirectory(Path directory);

    /** 创建一个 UTF-8 文本文件；已存在的对象不会被覆盖。 */
    Path createTextFile(Path file, String content);

    /** 在工作区内部移动文件或目录，不覆盖目标。 */
    void move(Path source, Path target);

    /** 将对象移动到本次计划步骤对应的回收站位置。 */
    TrashEntry moveToTrash(Path source, String planId, String stepId);

    /** 按回收站 ID 恢复对象；原位置存在时拒绝覆盖。 */
    Path restoreFromTrash(String trashId);

    /** 查询回收站条目。 */
    List<TrashEntry> listTrash();

    /** 读取共享集合状态；不存在的集合返回空列表。 */
    List<Map<String, Object>> readItems(String collection);

    /** 判断集合状态文件是否已经创建，即使内容为空。 */
    boolean hasItems(String collection);

    /** 原子替换共享集合状态。 */
    void writeItems(String collection, List<Map<String, Object>> items);

    /** 生成框架内唯一 ID。 */
    String newId();

    /** 将受保护路径转换为工作区相对路径。 */
    String relativePath(Path path);
}
