package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSettingsDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 校验并解压模块分发源码包。
 *
 * <p>ZIP 只在临时下载目录内展开，所有条目必须位于一个与 {@code moduleId} 同名的根目录
 * 下。服务拒绝绝对路径、空段、点段、重复条目、嵌套归档、构建产物和超限内容；即使条目
 * 被标记为符号链接，也只复制普通文件字节，不把链接元数据写入文件系统。</p>
 */
@Component
public class ModulePackageInstaller {

    static final long MAX_EXPANDED_BYTES = 100L * 1024L * 1024L;
    static final int MAX_FILES = 2000;
    static final long MAX_ENTRY_BYTES = 32L * 1024L * 1024L;
    static final long MAX_COMPRESSION_RATIO = 200L;
    private static final Pattern SAFE_ASSET = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:js|css)$"
    );
    private static final Set<String> UI_TOP_LEVEL_FIELDS = Set.of(
            "version", "view", "card", "examples", "settings", "script", "style"
    );
    private static final Set<String> UI_VIEW_FIELDS = Set.of("id", "caption", "icon");
    private static final Set<String> UI_CARD_FIELDS = Set.of(
            "title", "description", "badge", "icon", "queryId", "countKey", "unit"
    );
    private static final Set<String> UI_SETTING_BASE_FIELDS = Set.of(
            "key", "type", "label", "title", "description", "default"
    );
    private static final Set<String> UI_SETTING_TYPE_FIELDS = Set.of(
            "options", "min", "max", "placeholder", "minLength", "maxLength"
    );

    private static final Set<String> FORBIDDEN_SUFFIXES = Set.of(
            ".class", ".jar", ".war", ".exe", ".dll", ".so", ".dylib",
            ".bat", ".cmd", ".ps1", ".msi", ".lnk", ".zip"
    );

    private final ContractCatalog contractCatalog;
    private final ObjectMapper objectMapper;

    public ModulePackageInstaller(
            ContractCatalog contractCatalog,
            ObjectMapper objectMapper
    ) {
        this.contractCatalog = contractCatalog;
        this.objectMapper = objectMapper;
    }

    /**
     * 在校验包摘要后展开到隔离目录，并返回待提交的模块源码根目录。
     */
    public Path stage(
            byte[] packageBytes,
            String expectedModuleId,
            String expectedSha256,
            long expectedSize,
            Path stagingRoot
    ) {
        validateIdentity(expectedModuleId);
        if (packageBytes == null || packageBytes.length != expectedSize) {
            throw new ModulePackageException(
                    "PACKAGE_SIZE_MISMATCH",
                    "模块包大小与分发目录不一致"
            );
        }
        String actualHash = sha256(packageBytes);
        if (!actualHash.equalsIgnoreCase(expectedSha256)) {
            throw new ModulePackageException(
                    "PACKAGE_HASH_MISMATCH",
                    "模块包 SHA-256 与分发目录不一致"
            );
        }

        Path root = stagingRoot.toAbsolutePath().normalize();
        Path archive = root.resolve("module.zip");
        try {
            Files.createDirectories(root);
            Files.write(
                    archive,
                    packageBytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            extract(archive, root, expectedModuleId);
            validateExtracted(root.resolve(expectedModuleId), expectedModuleId);
            return root.resolve(expectedModuleId);
        } catch (ModulePackageException exception) {
            cleanup(root);
            throw exception;
        } catch (IOException exception) {
            cleanup(root);
            throw new ModulePackageException(
                    "PACKAGE_EXTRACTION_FAILED",
                    "模块包无法展开：" + exception.getMessage()
            );
        }
    }

    /** 清理单个下载任务的临时包、半成品和空目录，不递归跟随符号链接。 */
    public void cleanup(Path stagingRoot) {
        if (stagingRoot == null) {
            return;
        }
        Path root = stagingRoot.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            // 仅清理调用方指定的临时 staging 根目录；失败不覆盖已经确定的安装结果。
            FileSystemUtils.deleteRecursively(root);
        } catch (IOException exception) {
            // 下次任务使用独立 staging 目录，临时残留不改变模块提交结果。
        }
    }

    /**
     * 将已验证源码原子移动到 Module 根目录；目标存在时不覆盖。
     */
    public Path commit(Path stagedModule, Path moduleSourceDirectory) {
        Path sourceRoot = moduleSourceDirectory.toAbsolutePath().normalize();
        Path staged = stagedModule.toAbsolutePath().normalize();
        if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(staged)) {
            throw new ModulePackageException(
                    "INVALID_MODULE_PATH",
                    "待提交模块目录不存在或不是普通目录"
            );
        }
        String moduleId = staged.getFileName().toString();
        validateIdentity(moduleId);
        Path target = sourceRoot.resolve(moduleId).normalize();
        if (!target.startsWith(sourceRoot)) {
            throw new ModulePackageException(
                    "INVALID_MODULE_PATH",
                    "模块目标路径越界"
            );
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModulePackageException(
                    "MODULE_CONFLICT",
                    "模块源码目录已存在：" + moduleId
            );
        }
        try {
            Files.createDirectories(sourceRoot);
            try {
                return Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                return Files.move(staged, target);
            }
        } catch (IOException exception) {
            throw new ModulePackageException(
                    "MODULE_STAGE_COMMIT_FAILED",
                    "模块源码无法写入 Module 目录：" + exception.getMessage()
            );
        }
    }

    private void extract(Path archive, Path stagingRoot, String expectedModuleId) throws IOException {
        Set<String> seen = new HashSet<>();
        long totalBytes = 0;
        int fileCount = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = normalizeEntryName(entry.getName());
                if (!seen.add(entryName)) {
                    throw packageError("DUPLICATE_PACKAGE_ENTRY", "模块包包含重复条目：" + entryName);
                }
                String[] parts = entryName.split("/", -1);
                if (!expectedModuleId.equals(parts[0])) {
                    throw packageError(
                            "INVALID_PACKAGE_ROOT",
                            "模块包根目录必须为 " + expectedModuleId
                    );
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (++fileCount > MAX_FILES) {
                    throw packageError("PACKAGE_TOO_MANY_FILES", "模块包文件数量超过限制");
                }
                if (hasForbiddenSuffix(entryName)) {
                    throw packageError("FORBIDDEN_PACKAGE_FILE", "模块包包含禁止的文件：" + entryName);
                }
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_ENTRY_BYTES) {
                    throw packageError("PACKAGE_ENTRY_TOO_LARGE", "模块包条目过大：" + entryName);
                }
                if (entry.getCompressedSize() > 0
                        && declaredSize > 0
                        && declaredSize / entry.getCompressedSize() > MAX_COMPRESSION_RATIO) {
                    throw packageError("PACKAGE_COMPRESSION_RATIO", "模块包压缩比异常：" + entryName);
                }

                Path target = stagingRoot.resolve(entryName).normalize();
                if (!target.startsWith(stagingRoot)) {
                    throw packageError("INVALID_PACKAGE_PATH", "模块包路径越界：" + entryName);
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(
                             target,
                             StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE
                     )) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long entryBytes = 0;
                    while ((read = input.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            throw packageError(
                                    "PACKAGE_ENTRY_TOO_LARGE",
                                    "模块包条目过大：" + entryName
                            );
                        }
                        if (totalBytes > MAX_EXPANDED_BYTES) {
                            throw packageError(
                                    "PACKAGE_TOO_LARGE_EXPANDED",
                                    "模块包展开后超过大小限制"
                            );
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private void validateExtracted(Path moduleRoot, String moduleId) {
        Path pom = moduleRoot.resolve("pom.xml");
        Path service = moduleRoot.resolve(
                "src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule"
        );
        Path contract = moduleRoot.resolve(
                "src/main/resources/orderly-modules/" + moduleId + ".json"
        );
        Path ui = moduleRoot.resolve("src/main/resources/orderly-ui/module.json");
        Path script = moduleRoot.resolve("src/main/resources/orderly-ui/module.js");
        Path style = moduleRoot.resolve("src/main/resources/orderly-ui/module.css");
        requireFile(pom);
        requireFile(service);
        requireFile(contract);
        requireFile(ui);
        requireFile(script);
        requireFile(style);

        try {
            JsonNode contractNode = readStrictJson(contract);
            if (contractNode.has("system")) {
                throw packageError(
                        "SYSTEM_MODULE_FORBIDDEN",
                        "模块分发包不得声明系统模块元数据"
                );
            }
            ModuleContract parsedContract = contractCatalog.parseAndValidateModule(contractNode);
            if (!moduleId.equals(parsedContract.moduleId())) {
                throw packageError(
                        "MODULE_ID_MISMATCH",
                        "模块包目录名与契约 moduleId 不一致"
                );
            }
            JsonNode uiNode = readStrictJson(ui);
            validateUiManifest(uiNode, moduleRoot.resolve("src/main/resources/orderly-ui"));
            String serviceClass = Files.readString(service).trim();
            if (serviceClass.isBlank() || serviceClass.contains("\n") || serviceClass.contains("\r")) {
                throw packageError(
                        "INVALID_MODULE_SERVICE",
                        "模块 SPI 注册文件必须只声明一个执行器类"
                );
            }
        } catch (IOException exception) {
            throw packageError(
                    "INVALID_MODULE_PACKAGE",
                    "模块契约或 UI 清单无法读取：" + exception.getMessage()
            );
        } catch (ModulePackageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw packageError(
                    "INVALID_MODULE_PACKAGE",
                    "模块契约或 UI 清单校验失败：" + exception.getMessage()
            );
        }
    }

    private JsonNode readStrictJson(Path path) throws IOException {
        try (var parser = objectMapper.getFactory().createParser(Files.readAllBytes(path))) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return objectMapper.readTree(parser);
        }
    }

    private void validateUiManifest(JsonNode manifest, Path uiDirectory) {
        if (manifest == null || !manifest.isObject()) {
            throw packageError("INVALID_MODULE_UI", "模块 UI 清单必须是 JSON 对象");
        }
        rejectUnknownFields(manifest, UI_TOP_LEVEL_FIELDS, "模块 UI 清单");
        if (manifest.path("version").asInt(-1) != 1) {
            throw packageError("INVALID_MODULE_UI", "模块 UI version 必须为 1");
        }
        JsonNode view = manifest.path("view");
        if (!view.isObject()) {
            throw packageError("INVALID_MODULE_UI", "模块 UI 缺少 view 对象");
        }
        rejectUnknownFields(view, UI_VIEW_FIELDS, "模块 UI view");
        if (!view.path("id").asText("").matches("^[a-z][a-z0-9-]{0,63}$")
                || view.path("caption").asText("").isBlank()
                || view.path("icon").asText("").isBlank()) {
            throw packageError("INVALID_MODULE_UI", "模块 UI view 字段非法");
        }
        JsonNode card = manifest.path("card");
        if (!card.isMissingNode() && !card.isNull()) {
            if (!card.isObject()) {
                throw packageError("INVALID_MODULE_UI", "模块 UI card 必须是对象");
            }
            rejectUnknownFields(card, UI_CARD_FIELDS, "模块 UI card");
            for (String field : List.of("title", "description", "badge", "icon", "countKey", "unit")) {
                if (card.path(field).asText("").isBlank()) {
                    throw packageError("INVALID_MODULE_UI", "模块 UI card 缺少字段：" + field);
                }
            }
        }
        JsonNode examples = manifest.path("examples");
        if (!examples.isArray()) {
            throw packageError("INVALID_MODULE_UI", "模块 UI examples 必须是数组");
        }
        for (JsonNode example : examples) {
            if (!example.isObject()
                    || example.size() != 2
                    || example.path("label").asText("").isBlank()
                    || example.path("text").asText("").isBlank()) {
                throw packageError("INVALID_MODULE_UI", "模块 UI example 字段非法");
            }
        }
        validateSettingsShape(manifest.path("settings"));
        ModuleSettingsDefinition.parse(manifest.path("settings"), objectMapper);
        String script = manifest.path("script").asText("");
        String style = manifest.path("style").asText("");
        if (!SAFE_ASSET.matcher(script).matches()
                || !script.endsWith(".js")
                || !SAFE_ASSET.matcher(style).matches()
                || !style.endsWith(".css")) {
            throw packageError("INVALID_MODULE_UI", "模块 UI script/style 必须是安全单级资源名");
        }
        requireFile(uiDirectory.resolve(script));
        requireFile(uiDirectory.resolve(style));
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String description) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw packageError("INVALID_MODULE_UI", description + "包含未知字段：" + field);
            }
        });
    }

    private void validateSettingsShape(JsonNode settings) {
        if (settings.isMissingNode() || settings.isNull()) {
            return;
        }
        JsonNode fields;
        if (settings.isArray()) {
            fields = settings;
        } else if (settings.isObject()) {
            rejectUnknownFields(settings, Set.of("fields"), "模块 UI settings");
            fields = settings.path("fields");
            if (!fields.isArray()) {
                throw packageError("INVALID_MODULE_UI", "模块 UI settings.fields 必须是数组");
            }
        } else {
            throw packageError("INVALID_MODULE_UI", "模块 UI settings 必须是数组或对象");
        }
        for (JsonNode field : fields) {
            if (!field.isObject()) {
                throw packageError("INVALID_MODULE_UI", "模块 UI 设置字段必须是对象");
            }
            Set<String> allowed = new HashSet<>(UI_SETTING_BASE_FIELDS);
            allowed.addAll(UI_SETTING_TYPE_FIELDS);
            rejectUnknownFields(field, allowed, "模块 UI 设置字段");
        }
    }

    private void requireFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw packageError(
                    "MISSING_MODULE_FILE",
                    "模块包缺少必需文件：" + file.getFileName()
            );
        }
    }

    private String normalizeEntryName(String name) {
        if (name == null || name.isBlank() || name.contains("\\")) {
            throw packageError("INVALID_PACKAGE_PATH", "模块包包含非法路径");
        }
        String normalized = name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.contains("//")) {
            throw packageError("INVALID_PACKAGE_PATH", "模块包包含非法路径：" + name);
        }
        String[] parts = normalized.split("/", -1);
        for (String part : parts) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)
                    || part.startsWith(".")) {
                throw packageError("INVALID_PACKAGE_PATH", "模块包包含非法路径：" + name);
            }
        }
        return normalized;
    }

    private boolean hasForbiddenSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private void validateIdentity(String moduleId) {
        if (moduleId == null
                || !moduleId.matches("^module_[a-z0-9_]+$")
                || moduleId.startsWith("module_system_")) {
            throw packageError("INVALID_MODULE_ID", "模块 ID 非法或属于受保护系统模块");
        }
    }

    private ModulePackageException packageError(String code, String message) {
        return new ModulePackageException(code, message);
    }

    /** 可安全映射为安装任务状态的包校验异常。 */
    public static class ModulePackageException extends RuntimeException {
        private final String code;

        public ModulePackageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
