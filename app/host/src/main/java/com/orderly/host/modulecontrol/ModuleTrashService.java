package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderly.host.contract.SystemModulePolicy;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 将业务模块源码和构建产物移入模块专用回收站。
 *
 * <p>回收站位于应用数据目录的 {@code trash/modules}。移动前要求源与目标位于同一
 * 文件存储，避免跨卷移动退化为复制后删除；本服务不提供物理删除能力。</p>
 */
@Service
public class ModuleTrashService {

    private static final Pattern MODULE_ID = Pattern.compile("^module_[a-z0-9_]+$");
    private static final Pattern TRASH_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,127}$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Path moduleSourceDirectory;
    private final Path modulesDirectory;
    private final Path trashRoot;
    private final ObjectMapper objectMapper;

    public ModuleTrashService(
            WorkspaceRuntime runtime,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.modules-dir:target/orderly-modules}") String modulesDirectory,
            ObjectMapper objectMapper
    ) {
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
        this.modulesDirectory = Path.of(modulesDirectory).toAbsolutePath().normalize();
        this.trashRoot = runtime.dataRoot().resolve("trash/modules").toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(trashRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("初始化模块回收站失败：" + trashRoot, exception);
        }
    }

    /** 生成只包含安全字符的回收站条目 ID。 */
    public String newTrashId() {
        return STAMP.format(Instant.now()) + "-" + UUID.randomUUID();
    }

    /** 将模块源码目录移动到回收站；目标已存在时拒绝覆盖。 */
    public Path moveSource(String moduleId, String trashId) {
        validateIds(moduleId, trashId);
        Path source = moduleSourceDirectory.resolve(moduleId).normalize();
        if (!source.startsWith(moduleSourceDirectory)
                || Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModuleTrashException(
                    "MODULE_SOURCE_MISSING",
                    "模块源码目录不存在或不是普通目录：" + moduleId
            );
        }
        Path target = trashRoot.resolve(trashId).resolve(moduleId).normalize();
        requireInside(trashRoot, target, "模块回收站目标路径越界");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_CONFLICT",
                    "模块回收站条目已存在：" + trashId
            );
        }
        try {
            Files.createDirectories(target.getParent());
            requireSameFileStore(source, target.getParent());
            writeManifest(trashId, moduleId, source, null);
            movePath(source, target);
            return target;
        } catch (IOException exception) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_MOVE_FAILED",
                    "模块源码移入回收站失败：" + moduleId + "；" + exception.getMessage()
            );
        }
    }

    /** 重启前撤销移除，将源码从回收站移回原位置；原位置存在时拒绝覆盖。 */
    public void restoreSource(String moduleId, String trashId) {
        validateIds(moduleId, trashId);
        Path stored = trashRoot.resolve(trashId).resolve(moduleId).normalize();
        requireInside(trashRoot, stored, "模块回收站路径越界");
        Path target = moduleSourceDirectory.resolve(moduleId).normalize();
        if (!target.startsWith(moduleSourceDirectory)) {
            throw new ModuleTrashException("MODULE_SOURCE_PATH_INVALID", "模块源码目标路径越界");
        }
        if (!Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                return;
            }
            throw new ModuleTrashException(
                    "MODULE_TRASH_ENTRY_MISSING",
                    "模块回收站条目不存在：" + trashId
            );
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModuleTrashException(
                    "MODULE_SOURCE_CONFLICT",
                    "模块源码原位置已被占用：" + moduleId
            );
        }
        try {
            Files.createDirectories(target.getParent());
            requireSameFileStore(stored, target.getParent());
            movePath(stored, target);
        } catch (IOException exception) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_RESTORE_FAILED",
                    "从模块回收站恢复失败：" + moduleId + "；" + exception.getMessage()
            );
        }
    }

    /**
     * 将尚未加载的模块 JAR 移入回收站。
     *
     * @return true 表示存在 JAR 并已移动；false 表示没有需要移动的 JAR
     */
    public boolean moveJar(String moduleId, String trashId) {
        validateIds(moduleId, trashId);
        Path jar = modulesDirectory.resolve(moduleId + ".jar").normalize();
        if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModuleTrashException(
                    "MODULE_JAR_INVALID",
                    "模块 JAR 不是普通文件：" + jar.getFileName()
            );
        }
        Path target = trashRoot.resolve(trashId).resolve(jar.getFileName().toString()).normalize();
        requireInside(trashRoot, target, "模块回收站 JAR 路径越界");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_CONFLICT",
                    "模块回收站 JAR 已存在：" + trashId
            );
        }
        try {
            Files.createDirectories(target.getParent());
            requireSameFileStore(jar, target.getParent());
            writeManifest(trashId, moduleId, null, jar);
            movePath(jar, target);
            return true;
        } catch (IOException exception) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_MOVE_FAILED",
                    "模块 JAR 移入回收站失败：" + moduleId + "；" + exception.getMessage()
            );
        }
    }

    /** 返回模块回收站根目录，供宿主诊断和测试使用。 */
    public Path trashRoot() {
        return trashRoot;
    }

    private void validateIds(String moduleId, String trashId) {
        if (moduleId == null
                || !MODULE_ID.matcher(moduleId).matches()
                || SystemModulePolicy.hasReservedPrefix(moduleId)) {
            throw new ModuleTrashException(
                    "INVALID_MODULE_ID",
                    "模块 ID 非法或属于受保护系统模块：" + String.valueOf(moduleId)
            );
        }
        if (trashId == null || !TRASH_ID.matcher(trashId).matches()) {
            throw new ModuleTrashException("INVALID_TRASH_ID", "模块回收站 ID 非法");
        }
    }

    private void requireSameFileStore(Path source, Path targetParent) throws IOException {
        FileStore sourceStore = Files.getFileStore(source);
        FileStore targetStore = Files.getFileStore(targetParent);
        if (!sourceStore.equals(targetStore)) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_CROSS_VOLUME",
                    "模块源码与回收站不在同一文件存储，拒绝跨卷移动"
            );
        }
    }

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void requireInside(Path root, Path path, String message) {
        if (!path.startsWith(root)) {
            throw new ModuleTrashException("MODULE_TRASH_PATH_INVALID", message);
        }
    }

    private void writeManifest(
            String trashId,
            String moduleId,
            Path source,
            Path jar
    ) {
        Path directory = trashRoot.resolve(trashId).normalize();
        Path manifest = directory.resolve("manifest.json");
        try {
            Files.createDirectories(directory);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", 1);
            root.put("moduleId", moduleId);
            root.put("trashId", trashId);
            root.put("createdAt", Instant.now().toString());
            root.put("sourceDirectory", "Module/" + moduleId);
            if (jar != null) {
                root.put("jarFileName", jar.getFileName().toString());
            }
            Path temporary = manifest.resolveSibling("manifest.json.tmp");
            Files.writeString(
                    temporary,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        manifest,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ModuleTrashException(
                    "MODULE_TRASH_MANIFEST_FAILED",
                    "写入模块回收站清单失败：" + directory + "；" + exception.getMessage()
            );
        }
    }

    /** 模块回收站操作失败。 */
    public static class ModuleTrashException extends RuntimeException {
        private final String code;

        public ModuleTrashException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
