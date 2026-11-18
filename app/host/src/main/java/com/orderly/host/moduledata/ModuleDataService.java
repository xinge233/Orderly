package com.orderly.host.moduledata;

import com.orderly.host.api.ApiException;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提供模块自管运行资源的通用读写边界，不包含具体模块业务。
 *
 * <p>所有路径最终经 {@link WorkspaceRuntime#resolveWorkspacePath} 限制在应用沙箱。
 * 资源路径会去掉前导斜杠并按段校验，拒绝上跳、空段和非法字符；写入只允许受支持
 * 图片/视频类型且不超过 100 MB，并拒绝覆盖已有文件。清理只移入项目回收站。</p>
 */
@Service
public class ModuleDataService {

    private static final long MAX_BYTES = 100L * 1024L * 1024L;
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "mp4", "video/mp4",
            "webm", "video/webm"
    );

    private final ModuleControlService modules;
    private final WorkspaceRuntime runtime;

    public ModuleDataService(ModuleControlService modules, WorkspaceRuntime runtime) {
        this.modules = modules;
        this.runtime = runtime;
    }

    /**
     * 打开已启用业务模块的现有普通资源。
     *
     * <p>解析路径后要求非符号链接普通文件，并按扩展名返回内容类型。只读，不创建或
     * 替换文件。</p>
     */
    public ModuleDataAsset open(String moduleId, String relativePath) {
        modules.requireEnabledBusinessModule(moduleId);
        Path file = resolve(moduleId, relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "MODULE_DATA_NOT_FOUND",
                    "模块资源不存在：" + moduleId + "/" + relativePath
            );
        }
        try {
            return new ModuleDataAsset(
                    relativePath,
                    contentType(file),
                    Files.size(file),
                    file
            );
        } catch (Exception exception) {
            throw new IllegalStateException("读取模块资源摘要失败", exception);
        }
    }

    /**
     * 暂存一个模块二进制资源。
     *
     * <p>要求模块启用、内容非空且不超过 100 MB、MIME 与扩展名精确匹配。底层创建采用
     * “目标不存在才写入”语义，不覆盖已有资源；任何失败都不会替换现有文件。</p>
     */
    public ModuleDataAsset stage(
            String moduleId,
            String relativePath,
            String contentType,
            byte[] content
    ) {
        modules.requireEnabledBusinessModule(moduleId);
        if (content == null || content.length == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_MODULE_DATA",
                    "模块资源内容不能为空"
            );
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "MODULE_DATA_TOO_LARGE",
                    "模块资源超过 100 MB 上限"
            );
        }
        Path file = resolve(moduleId, relativePath);
        String normalizedType = requireContentType(contentType, relativePath);
        try {
            runtime.createBinaryFile(file, content);
            return new ModuleDataAsset(
                    relativePath,
                    normalizedType,
                    content.length,
                    file
            );
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_DATA_EXISTS",
                    exception.getMessage()
            );
        }
    }

    /**
     * 清理模块暂存资源，仅当路径存在时移入项目回收站。
     *
     * <p>该方法服务于执行/回滚清理，不检查模块当前启用状态；路径仍必须位于工作区。
     * 路径已不存在时重复调用为 no-op，但无锁，不能替代并发写入协调。</p>
     */
    public void cleanup(String moduleId, String relativePath, String planId, String stepId) {
        Path file = resolve(moduleId, relativePath);
        if (runtime.exists(file)) {
            runtime.moveToTrash(file, planId, stepId);
        }
    }

    /**
     * 将模块相对资源解析为应用自管工作区内的路径。
     *
     * <p>先做段级白名单和上跳检查，再交给 WorkspaceRuntime 做最终边界及现有符号链接
     * 校验。本方法只解析路径，不读写文件。</p>
     */
    public Path resolve(String moduleId, String relativePath) {
        String normalized = requireRelativePath(relativePath);
        return runtime.resolveWorkspacePath("modules/" + moduleId + "/" + normalized);
    }

    /** 规范化并校验相对路径和每个路径段，拒绝目录路径及任何上跳形式。 */
    private String requireRelativePath(String relativePath) {
        String value = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isBlank() || value.endsWith("/") || value.contains("//")) {
            throw invalidPath();
        }
        Path normalized;
        try {
            normalized = Path.of(value).normalize();
        } catch (RuntimeException exception) {
            throw invalidPath();
        }
        if (normalized.isAbsolute()
                || normalized.startsWith("..")
                || normalized.toString().contains("..")) {
            throw invalidPath();
        }
        String[] segments = value.split("/");
        for (String segment : segments) {
            if (!SAFE_SEGMENT.matcher(segment).matches()) {
                throw invalidPath();
            }
        }
        return String.join("/", segments);
    }

    /** 要求声明的 MIME 与扩展名白名单完全匹配，并返回规范化的 MIME。 */
    private String requireContentType(String contentType, String relativePath) {
        String value = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String expected = CONTENT_TYPES.get(extension(relativePath));
        if (expected == null || !expected.equals(value)) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "MODULE_DATA_TYPE_UNSUPPORTED",
                    "仅允许 jpg/png/webp 图片与 mp4/webm 视频"
            );
        }
        return value;
    }

    /** 根据扩展名推断响应内容类型，未知类型保留为二进制流。 */
    private String contentType(Path file) {
        String type = CONTENT_TYPES.get(extension(file.getFileName().toString()));
        return type == null ? "application/octet-stream" : type;
    }

    /** 提取并小写最后一个点之后的扩展名。 */
    private String extension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 构造统一的非法模块资源路径错误。 */
    private ApiException invalidPath() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_MODULE_DATA_PATH",
                "模块资源路径非法"
        );
    }

    /** 已通过边界检查的模块资源描述，file 仅供宿主流式响应使用。 */
    public record ModuleDataAsset(
            String relativePath,
            String contentType,
            long size,
            Path file
    ) {
    }
}
