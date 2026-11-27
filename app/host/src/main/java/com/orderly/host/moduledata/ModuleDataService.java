package com.orderly.host.moduledata;

import com.orderly.host.api.ApiException;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提供模块自管运行资源的通用读写边界，不包含具体模块业务。
 *
 * <p>所有路径最终经 {@link WorkspaceRuntime#resolveWorkspacePath} 限制在应用沙箱。
 * 资源路径会去掉前导斜杠并按段校验，拒绝上跳、空段和非法字符；单文件不超过
 * 64 MiB，并拒绝覆盖已有暂存文件。文件类型由具体模块动作在执行前继续校验。
 * 清理只移入项目回收站。</p>
 */
@Service
public class ModuleDataService {

    private static final long MAX_BYTES = 64L * 1024L * 1024L;
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Map<String, ContentRule> CONTENT_RULES = Map.ofEntries(
            Map.entry("pdf", new ContentRule(
                    Set.of("application/pdf"),
                    "application/pdf"
            )),
            Map.entry("docx", new ContentRule(
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )),
            Map.entry("xlsx", new ContentRule(
                    Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )),
            Map.entry("pptx", new ContentRule(
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )),
            Map.entry("txt", new ContentRule(
                    Set.of("text/plain"),
                    "text/plain; charset=utf-8"
            )),
            Map.entry("md", new ContentRule(
                    Set.of("text/markdown", "text/plain", "text/x-markdown"),
                    "text/markdown; charset=utf-8"
            )),
            Map.entry("csv", new ContentRule(
                    Set.of("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel"),
                    "text/csv; charset=utf-8"
            )),
            Map.entry("json", new ContentRule(
                    Set.of("application/json", "text/json", "text/plain"),
                    "application/json"
            )),
            Map.entry("png", new ContentRule(
                    Set.of("image/png"),
                    "image/png"
            )),
            Map.entry("jpg", new ContentRule(
                    Set.of("image/jpeg"),
                    "image/jpeg"
            )),
            Map.entry("jpeg", new ContentRule(
                    Set.of("image/jpeg"),
                    "image/jpeg"
            )),
            Map.entry("webp", new ContentRule(
                    Set.of("image/webp"),
                    "image/webp"
            )),
            Map.entry("mp4", new ContentRule(
                    Set.of("video/mp4"),
                    "video/mp4"
            )),
            Map.entry("webm", new ContentRule(
                    Set.of("video/webm"),
                    "video/webm"
            ))
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
     * <p>要求模块启用、内容非空且不超过 64 MiB。底层创建采用“目标不存在才写入”
     * 语义，不覆盖已有资源；任何失败都不会替换现有文件。</p>
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
                    "模块资源超过 64 MiB 上限"
            );
        }
        Path file = resolve(moduleId, relativePath);
        String normalizedType = requireContentType(contentType, relativePath);
        boolean opaque = "application/octet-stream".equals(normalizedType);
        if (!opaque || CONTENT_RULES.containsKey(extension(relativePath))) {
            requireContentSignature(relativePath, content);
        }
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

    /** 保留浏览器提供的规范化 MIME；未知类型回退为二进制流。 */
    private String requireContentType(String contentType, String relativePath) {
        String value = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(value)) {
            return value;
        }
        ContentRule rule = CONTENT_RULES.get(extension(relativePath));
        if (rule == null || !rule.acceptedTypes().contains(value)) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "MODULE_DATA_TYPE_UNSUPPORTED",
                    "文件扩展名与内容类型不在资料库白名单内"
            );
        }
        return rule.responseType();
    }

    /** 校验文件头或文本编码与扩展名一致，不把客户端 MIME 当作可信内容证明。 */
    private void requireContentSignature(String relativePath, byte[] content) {
        String extension = extension(relativePath);
        boolean valid = switch (extension) {
            case "pdf" -> startsWith(content, '%', 'P', 'D', 'F', '-');
            case "docx", "xlsx", "pptx" -> startsWith(content, 'P', 'K')
                    && content.length >= 3
                    && (content[2] == 3 || content[2] == 5 || content[2] == 7);
            case "png" -> startsWith(
                    content,
                    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
            );
            case "jpg", "jpeg" -> startsWith(content, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            case "webp" -> startsWith(content, 'R', 'I', 'F', 'F')
                    && matchesAt(content, 8, 'W', 'E', 'B', 'P');
            case "mp4" -> matchesAt(content, 4, 'f', 't', 'y', 'p');
            case "webm" -> startsWith(content, (byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3);
            case "txt", "md", "csv", "json" -> isUtf8Text(content);
            default -> false;
        };
        if (!valid) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "MODULE_DATA_SIGNATURE_UNSUPPORTED",
                    "文件内容与扩展名不匹配或文本编码不是 UTF-8"
            );
        }
    }

    /** 根据扩展名推断响应内容类型，未知类型保留为二进制流。 */
    private String contentType(Path file) {
        ContentRule rule = CONTENT_RULES.get(extension(file.getFileName().toString()));
        return rule == null ? "application/octet-stream" : rule.responseType();
    }

    /** 提取并小写最后一个点之后的扩展名。 */
    private String extension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 比较固定文件头；长度不足时返回 false。 */
    private boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((content[index] & 0xFF) != (prefix[index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** 校验任意偏移处的固定字节序列。 */
    private boolean matchesAt(byte[] content, int offset, int... expected) {
        if (offset < 0 || content.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((content[offset + index] & 0xFF) != (expected[index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** 只接受无 NUL 的合法 UTF-8 文本。 */
    private boolean isUtf8Text(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            return text.indexOf('\0') < 0;
        } catch (CharacterCodingException exception) {
            return false;
        }
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

    /** 单个扩展名允许的请求 MIME 与读取端点返回 MIME。 */
    private record ContentRule(Set<String> acceptedTypes, String responseType) {
    }
}
