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

/** 提供模块自管运行资源的通用读写边界，不包含具体模块业务。 */
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

    public void cleanup(String moduleId, String relativePath, String planId, String stepId) {
        Path file = resolve(moduleId, relativePath);
        if (runtime.exists(file)) {
            runtime.moveToTrash(file, planId, stepId);
        }
    }

    public Path resolve(String moduleId, String relativePath) {
        String normalized = requireRelativePath(relativePath);
        return runtime.resolveWorkspacePath("modules/" + moduleId + "/" + normalized);
    }

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

    private String contentType(Path file) {
        String type = CONTENT_TYPES.get(extension(file.getFileName().toString()));
        return type == null ? "application/octet-stream" : type;
    }

    private String extension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private ApiException invalidPath() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_MODULE_DATA_PATH",
                "模块资源路径非法"
        );
    }

    public record ModuleDataAsset(
            String relativePath,
            String contentType,
            long size,
            Path file
    ) {
    }
}
