package com.orderly.host.theme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 启动时扫描 orderly.themes-dir 的直接主题目录。
 *
 * <p>不读取 classpath、不监听文件系统。单个主题包的格式或资源问题会记录为
 * ThemeIssue；重复 id 和多个默认主题仍是目录级阻塞冲突。</p>
 */
@Component
public class ThemeCatalog {

    static final int SUPPORTED_SCHEMA_VERSION = 1;
    static final List<Integer> SUPPORTED_SCHEMA_VERSIONS = List.of(SUPPORTED_SCHEMA_VERSION);

    private static final Pattern THEME_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern STYLE_PATH = Pattern.compile(
            "(?:[a-z0-9][a-z0-9.-]*/)*[a-z0-9][a-z0-9.-]*\\.css"
    );
    private static final Pattern ASSET_PATH = Pattern.compile(
            "assets/(?:[a-z0-9][a-z0-9.-]*/)*[a-z0-9][a-z0-9.-]*\\."
                    + "(?:png|jpe?g|webp|gif|avif|woff2?|ttf|otf)"
    );
    private static final Set<String> REQUIRED_STYLES = Set.of("tokens.css", "motion.css");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "id", "label", "note", "order", "default",
            "colorScheme", "browserColor", "styles", "assets"
    );
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("avif", "image/avif"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf")
    );
    private static final long MAX_ASSET_BYTES = 8L * 1024L * 1024L;

    private final ThemeCatalogSnapshot snapshot;
    private final Map<String, ThemeDefinition> themesById;

    public ThemeCatalog(
            ObjectMapper objectMapper,
            @Value("${orderly.themes-dir:themes}") String themesDirectory
    ) {
        Path sourceDirectory = Path.of(themesDirectory).toAbsolutePath().normalize();
        Map<String, ThemeDefinition> loaded = new LinkedHashMap<>();
        List<ThemeIssue> issues = new ArrayList<>();
        scanDirectory(objectMapper, sourceDirectory, loaded, issues);

        long defaultCount = loaded.values().stream().filter(ThemeDefinition::defaultTheme).count();
        if (defaultCount > 1) {
            throw new IllegalStateException("主题目录只能声明一个 default: true");
        }

        List<ThemeDefinition> ordered = loaded.values().stream()
                .sorted(Comparator.comparingInt(ThemeDefinition::order)
                        .thenComparing(ThemeDefinition::id))
                .toList();
        this.snapshot = new ThemeCatalogSnapshot(
                ordered.stream().map(ThemeDefinition::summary).toList(),
                issues,
                ordered.stream().noneMatch(ThemeDefinition::defaultTheme)
        );
        this.themesById = Map.copyOf(loaded);
    }

    public ThemeCatalogSnapshot snapshot() {
        return snapshot;
    }

    public List<ThemeSummary> summaries() {
        return snapshot.items();
    }

    public ThemeDefinition require(String id) {
        ThemeDefinition theme = themesById.get(id);
        if (theme == null) {
            throw new IllegalArgumentException("主题不存在：" + id);
        }
        return theme;
    }

    public boolean contains(String id) {
        return themesById.containsKey(id);
    }

    /**
     * 只读取主题清单登记的样式或静态资源。
     *
     * <p>请求路径在进入文件系统前会再次执行相对路径、链接和登记白名单检查。</p>
     */
    public Optional<ThemeAsset> readAsset(String id, String requestedPath) {
        ThemeDefinition theme = themesById.get(id);
        if (theme == null) {
            return Optional.empty();
        }
        String assetPath = requestedPath == null ? "" : requestedPath;
        if (assetPath.startsWith("/")) {
            assetPath = assetPath.substring(1);
        }
        if (!theme.styles().contains(assetPath) && !theme.assets().contains(assetPath)) {
            return Optional.empty();
        }
        if (!isSafeRelativePath(assetPath)
                || (!STYLE_PATH.matcher(assetPath).matches()
                && !ASSET_PATH.matcher(assetPath).matches())) {
            return Optional.empty();
        }
        Path root = theme.rootPath();
        Path resolved = root.resolve(assetPath).normalize();
        if (!resolved.startsWith(root) || hasUnsafePathComponent(root, assetPath)) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            long size = Files.size(resolved);
            if (size < 0 || size > MAX_ASSET_BYTES) {
                return Optional.empty();
            }
            try (InputStream input = Files.newInputStream(resolved)) {
                byte[] content = input.readNBytes((int) MAX_ASSET_BYTES + 1);
                if (content.length > MAX_ASSET_BYTES) {
                    return Optional.empty();
                }
                return Optional.of(new ThemeAsset(contentType(assetPath), content));
            }
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    private void scanDirectory(
            ObjectMapper objectMapper,
            Path directory,
            Map<String, ThemeDefinition> loaded,
            List<ThemeIssue> issues
    ) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isLinkOrReparsePoint(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            issues.add(ThemeIssue.of(
                    directory.getFileName() == null ? "themes" : directory.getFileName().toString(),
                    "THEMES_DIRECTORY_INVALID",
                    "主题目录必须是普通本地目录：" + directory
            ));
            return;
        }

        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> candidates = entries
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path candidate : candidates) {
                String directoryId = candidate.getFileName().toString();
                if (directoryId.startsWith("_") || directoryId.startsWith(".")) {
                    continue;
                }
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    if (isLinkOrReparsePoint(candidate)) {
                        issues.add(ThemeIssue.of(
                                directoryId,
                                "THEME_PATH_UNSAFE",
                                "主题包不能是符号链接：" + directoryId
                        ));
                    }
                    continue;
                }
                try {
                    ThemeDefinition theme = loadPackage(objectMapper, candidate, directoryId);
                    if (loaded.putIfAbsent(theme.id(), theme) != null) {
                        throw new IllegalStateException("检测到重复主题 id：" + theme.id());
                    }
                } catch (ThemeLoadFailure failure) {
                    issues.add(failure.issue());
                }
            }
        } catch (IOException exception) {
            issues.add(ThemeIssue.of(
                    directory.getFileName() == null ? "themes" : directory.getFileName().toString(),
                    "THEMES_DIRECTORY_UNREADABLE",
                    "无法读取主题目录：" + exception.getMessage()
            ));
        }
    }

    private ThemeDefinition loadPackage(
            ObjectMapper objectMapper,
            Path themeRoot,
            String directoryId
    ) throws ThemeLoadFailure {
        if (!THEME_ID.matcher(directoryId).matches()) {
            throw ThemeLoadFailure.of(
                    directoryId,
                    "INVALID_THEME_ID",
                    "主题目录名不是合法 id：" + directoryId
            );
        }
        Path manifest = themeRoot.resolve("theme.json");
        if (isLinkOrReparsePoint(themeRoot) || isLinkOrReparsePoint(manifest)) {
            throw ThemeLoadFailure.of(directoryId, "THEME_PATH_UNSAFE", "主题清单不能是符号链接");
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw ThemeLoadFailure.of(
                    directoryId,
                    "MISSING_THEME_MANIFEST",
                    "主题包缺少普通文件 theme.json"
            );
        }
        JsonNode root;
        try (InputStream input = Files.newInputStream(manifest)) {
            root = objectMapper.readTree(input);
        } catch (JsonProcessingException exception) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_THEME_MANIFEST", "theme.json 不是合法 JSON");
        } catch (IOException exception) {
            throw ThemeLoadFailure.of(directoryId, "THEME_RESOURCE_UNREADABLE", "无法读取 theme.json");
        }
        return parseAndValidate(root, themeRoot, directoryId);
    }

    private ThemeDefinition parseAndValidate(
            JsonNode root,
            Path themeRoot,
            String directoryId
    ) throws ThemeLoadFailure {
        if (root == null || !root.isObject()) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_THEME_MANIFEST", "theme.json 根节点必须是对象");
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!MANIFEST_FIELDS.contains(field)) {
                throw ThemeLoadFailure.of(
                        directoryId,
                        "INVALID_THEME_MANIFEST",
                        "主题清单包含未知字段：" + field
                );
            }
        }

        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw ThemeLoadFailure.of(
                    directoryId,
                    "INVALID_SCHEMA_VERSION",
                    "主题必须声明整数 schemaVersion"
            );
        }
        int schemaVersion = versionNode.asInt();
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new ThemeLoadFailure(new ThemeIssue(
                    directoryId,
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "主题需要 schemaVersion " + schemaVersion + "，当前程序仅支持 1",
                    SUPPORTED_SCHEMA_VERSIONS,
                    schemaVersion
            ));
        }

        String id = optionalText(root, "id", directoryId, "");
        if (id.isBlank()) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_THEME_MANIFEST", "主题缺少文本字段：id");
        }
        if (!THEME_ID.matcher(id).matches()) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_THEME_ID", "主题 id 非法：" + id);
        }
        if (!id.equals(directoryId)) {
            throw ThemeLoadFailure.of(
                    directoryId,
                    "THEME_ID_DIRECTORY_MISMATCH",
                    "主题目录名必须与清单 id 一致：" + directoryId + " != " + id
            );
        }
        String label = requiredText(root, "label", directoryId);
        String note = optionalText(root, "note", directoryId, "");
        int order = requiredNonNegativeInteger(root, "order", directoryId);
        boolean defaultTheme = optionalBoolean(root, "default", directoryId, false);
        String colorScheme = optionalText(root, "colorScheme", directoryId, "");
        if (!colorScheme.isBlank() && !Set.of("light", "dark").contains(colorScheme)) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_COLOR_SCHEME", "colorScheme 只能是 light 或 dark");
        }
        String browserColor = optionalText(root, "browserColor", directoryId, "");
        if (root.has("browserColor") && browserColor.isBlank()) {
            throw ThemeLoadFailure.of(directoryId, "INVALID_THEME_MANIFEST", "browserColor 不能为空");
        }
        List<String> styles = pathList(root, "styles", true, directoryId);
        if (!new HashSet<>(styles).containsAll(REQUIRED_STYLES)) {
            throw ThemeLoadFailure.of(
                    directoryId,
                    "MISSING_REQUIRED_STYLE",
                    "styles 必须登记 tokens.css 与 motion.css"
            );
        }
        List<String> assets = pathList(root, "assets", false, directoryId);

        for (String style : styles) {
            if (!STYLE_PATH.matcher(style).matches()) {
                throw ThemeLoadFailure.of(
                        directoryId,
                        "INVALID_STYLE_PATH",
                        "主题样式路径必须是无逃逸的相对 CSS 路径：" + style
                );
            }
            requireThemeResource(themeRoot, style, directoryId, "THEME_STYLE_UNREADABLE");
        }
        for (String asset : assets) {
            if (!ASSET_PATH.matcher(asset).matches()) {
                throw ThemeLoadFailure.of(
                        directoryId,
                        "INVALID_ASSET_PATH",
                        "主题资源必须位于 assets/ 且为允许的静态类型：" + asset
                );
            }
            requireThemeResource(themeRoot, asset, directoryId, "THEME_ASSET_UNREADABLE");
        }

        return new ThemeDefinition(
                schemaVersion,
                id,
                label,
                note,
                order,
                defaultTheme,
                colorScheme,
                browserColor,
                styles,
                assets,
                themeRoot
        );
    }

    private List<String> pathList(
            JsonNode root,
            String field,
            boolean required,
            String id
    ) throws ThemeLoadFailure {
        JsonNode node = root.get(field);
        if (node == null) {
            if (required) {
                throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题缺少数组字段：" + field);
            }
            return List.of();
        }
        if (!node.isArray()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题字段必须是数组：" + field);
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题路径必须是非空文本：" + field);
            }
            String path = value.asText();
            if (!isSafeRelativePath(path)) {
                throw ThemeLoadFailure.of(
                        id,
                        "INVALID_RESOURCE_PATH",
                        "主题路径必须使用正斜杠且不得越过主题包：" + path
                );
            }
            if (!seen.add(path)) {
                throw ThemeLoadFailure.of(id, "DUPLICATE_RESOURCE_PATH", "主题路径重复：" + path);
            }
            values.add(path);
        }
        if (required && values.isEmpty()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "styles 不能为空");
        }
        return List.copyOf(values);
    }

    private void requireThemeResource(
            Path themeRoot,
            String relativePath,
            String id,
            String code
    ) throws ThemeLoadFailure {
        if (hasUnsafePathComponent(themeRoot, relativePath)) {
            throw ThemeLoadFailure.of(id, "THEME_PATH_UNSAFE", "主题资源不能经过符号链接：" + relativePath);
        }
        Path resolved = themeRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(themeRoot)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw ThemeLoadFailure.of(id, code, "主题资源缺失或不是普通文件：" + relativePath);
        }
        try (InputStream input = Files.newInputStream(resolved)) {
            if (input.read() < 0) {
                throw ThemeLoadFailure.of(id, code, "主题资源为空或不可读：" + relativePath);
            }
        } catch (IOException | SecurityException exception) {
            throw ThemeLoadFailure.of(id, code, "主题资源不可读：" + relativePath);
        }
    }

    private boolean hasUnsafePathComponent(Path root, String relativePath) {
        Path current = root;
        if (isLinkOrReparsePoint(current)) {
            return true;
        }
        for (Path segment : Path.of(relativePath)) {
            current = current.resolve(segment);
            if (isLinkOrReparsePoint(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLinkOrReparsePoint(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return !path.toRealPath().equals(normalized);
        } catch (IOException | SecurityException exception) {
            return true;
        }
    }

    private boolean isSafeRelativePath(String path) {
        if (path.isBlank()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains("\\")
                || path.contains("%")
                || path.contains(":")
                || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.get(extension);
    }

    private String requiredText(JsonNode root, String field, String id) throws ThemeLoadFailure {
        String value = optionalText(root, field, id, "");
        if (value.isBlank()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题缺少文本字段：" + field);
        }
        return value.trim();
    }

    private String optionalText(
            JsonNode root,
            String field,
            String id,
            String fallback
    ) throws ThemeLoadFailure {
        JsonNode node = root.get(field);
        if (node == null) {
            return fallback;
        }
        if (!node.isTextual()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题字段必须是文本：" + field);
        }
        return node.asText();
    }

    private int requiredNonNegativeInteger(JsonNode root, String field, String id)
            throws ThemeLoadFailure {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题缺少整数 " + field);
        }
        int value = node.asInt();
        if (value < 0) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题 " + field + " 不能为负数");
        }
        return value;
    }

    private boolean optionalBoolean(
            JsonNode root,
            String field,
            String id,
            boolean fallback
    ) throws ThemeLoadFailure {
        JsonNode node = root.get(field);
        if (node == null) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题字段必须是布尔值：" + field);
        }
        return node.asBoolean();
    }
}
