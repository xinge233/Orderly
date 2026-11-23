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
 * 启动时扫描 {@code orderly.themes-dir} 直接子目录的主题目录索引。
 *
 * <p>不读取 classpath、不打包用户主题，也不监听文件系统热更新。单个主题包的格式或
 * 资源问题会记录为 {@link ThemeIssue} 并隔离该包；重复 {@code id} 和多个
 * {@code default: true} 是目录级阻塞冲突。索引没有可用默认主题时，
 * {@code builtInFallbackActive} 为真，由前端启用内置安全外观。</p>
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
    private static final Set<String> AI_ICON_IDS = Set.of(
            "spark", "route", "shield", "modules"
    );
    private static final Set<String> REQUIRED_STYLES = Set.of("tokens.css", "motion.css");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "id", "label", "note", "order", "default",
            "colorScheme", "browserColor", "styles", "aiIconAssets", "assets"
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

    /**
     * 一次性扫描主题目录并建立稳定目录快照。
     *
     * <p>目录缺失或没有任何可用默认主题不会阻止启动，而是由快照标志触发内置回退。
     * 重复主题 ID 或声明多个默认主题会抛出异常并阻止启动；其他单包问题只进入
     * {@code issues}。构造完成后索引不再随磁盘变化。</p>
     */
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

    /** 返回启动时固定的主题摘要、局部问题和内置回退标志；调用只读。 */
    public ThemeCatalogSnapshot snapshot() {
        return snapshot;
    }

    /** 返回按 {@code order}、再按 ID 排序的可用主题摘要列表。 */
    public List<ThemeSummary> summaries() {
        return snapshot.items();
    }

    /**
     * 读取一个已成功加载的主题定义。
     *
     * <p>缺失 ID 抛出参数异常；返回定义中的根路径仅供宿主资源读取，不作为公开 API
     * 返回给前端。查找不触发磁盘扫描或热更新。</p>
     */
    public ThemeDefinition require(String id) {
        ThemeDefinition theme = themesById.get(id);
        if (theme == null) {
            throw new IllegalArgumentException("主题不存在：" + id);
        }
        return theme;
    }

    /** 判断当前启动快照是否包含指定主题 ID；不扫描目录。 */
    public boolean contains(String id) {
        return themesById.containsKey(id);
    }

    /**
     * 只读取主题清单登记的样式或静态资源。
     *
     * <p>请求路径必须先去前导斜杠，再同时通过清单白名单、CSS/图片/字体扩展名、相对
     * 路径、逐段链接组件和普通文件检查。读取上限为 8 MiB。主题、登记项、文件或安全
     * 校验任一不满足时返回空，不暴露磁盘路径；I/O 与安全异常也按不可提供处理。方法
     * 每次读取当前文件，不承诺绕过检查后的并发文件替换。</p>
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

    /**
     * 扫描根目录的直接主题包并累积可隔离问题。
     *
     * <p>根目录缺失视为无主题；根目录为链接、重解析点或非目录时形成问题。候选按名称
     * 稳定排序，忽略下划线/点开头目录和非目录项。单个包抛出的
     * {@link ThemeLoadFailure} 被转换为问题；重复 ID 等目录级错误继续抛出并阻止启动。</p>
     */
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

    /**
     * 读取并验证一个主题包。
     *
     * <p>目录名必须先是合法主题 ID；{@code theme.json} 必须是普通、非链接文件并包含
     * 合法 JSON。任何单包格式、版本或资源错误都转换为 {@link ThemeLoadFailure}。</p>
     */
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

    /**
     * 校验主题清单字段、版本和全部登记资源。
     *
     * <p>清单禁止未知字段；目录名必须等于清单 ID；{@code styles} 必须登记
     * {@code tokens.css} 与 {@code motion.css}。所有资源和样式均须存在于包内且非空。
     * 任一项失败都拒绝整个主题包，不生成部分可用定义。</p>
     */
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
        Map<String, String> aiIconAssets = aiIconAssetMap(root, directoryId);

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
        for (Map.Entry<String, String> entry : aiIconAssets.entrySet()) {
            String asset = entry.getValue();
            if (!assets.contains(asset)) {
                throw ThemeLoadFailure.of(
                        directoryId,
                        "AI_ICON_ASSET_UNREGISTERED",
                        "AI 图标资源必须先登记在 assets：" + entry.getKey() + "=" + asset
                );
            }
            if (!asset.equals("assets/ai/icons/" + entry.getKey() + ".png")) {
                throw ThemeLoadFailure.of(
                        directoryId,
                        "INVALID_AI_ICON_ASSET",
                        "AI 图标资源文件名必须与图标 ID 一致：" + entry.getKey() + "=" + asset
                );
            }
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
                aiIconAssets,
                themeRoot
        );
    }

    /**
     * 读取可选的 AI 图标资源映射。
     *
     * <p>字段缺失时返回空映射以兼容旧主题。字段存在时必须是以四个固定图标 ID 为键的
     * 对象，值必须是无逃逸的 PNG 路径。路径是否已登记在 assets 由调用方继续检查。</p>
     */
    private Map<String, String> aiIconAssetMap(
            JsonNode root,
            String id
    ) throws ThemeLoadFailure {
        JsonNode node = root.get("aiIconAssets");
        if (node == null) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw ThemeLoadFailure.of(
                    id,
                    "INVALID_AI_ICON_ASSETS",
                    "aiIconAssets 必须是对象"
            );
        }
        Map<String, String> assets = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String iconId = field.getKey();
            if (!AI_ICON_IDS.contains(iconId)) {
                throw ThemeLoadFailure.of(
                        id,
                        "INVALID_AI_ICON_ID",
                        "不支持的 AI 图标 ID：" + iconId
                );
            }
            JsonNode value = field.getValue();
            if (!value.isTextual() || value.asText().isBlank()) {
                throw ThemeLoadFailure.of(
                        id,
                        "INVALID_AI_ICON_ASSET",
                        "AI 图标资源必须是非空路径：" + iconId
                );
            }
            String path = value.asText();
            if (!isSafeRelativePath(path)) {
                throw ThemeLoadFailure.of(
                        id,
                        "INVALID_AI_ICON_ASSET",
                        "AI 图标资源路径不安全：" + path
                );
            }
            assets.put(iconId, path);
        }
        return Map.copyOf(assets);
    }

    /**
     * 读取并校验资源路径数组。
     *
     * <p>字段缺失时，仅必填数组失败；存在时必须是文本数组，每项非空、唯一且使用安全
     * 正斜杠相对路径。必填数组还不允许为空。重复、逃逸或反斜杠路径都会拒绝主题包。</p>
     */
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

    /**
     * 验证主题资源位于包内、不是链接且可读取非空内容。
     *
     * <p>路径逐段检查链接组件，最终文件使用不跟随链接的普通文件检查，并读取一个字节
     * 确认非空。缺失、空文件或 I/O 失败按调用方提供的错误码转换为局部主题问题。</p>
     */
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

    /**
     * 检查主题根及相对路径的每一段是否经过链接或重解析点。
     *
     * <p>相对路径必须已经通过安全格式校验；本方法只沿词法段检查现有文件系统项，不读取
     * 资源内容。任一段是链接或无法确认真实路径时返回真。</p>
     */
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

    /**
     * 判断现有路径是否为符号链接或规范化后的重解析点。
     *
     * <p>不存在的路径返回假，由后续普通文件或目录检查决定失败。真实路径无法解析时
     * 保守视为不安全，避免在边界不明时继续读取。</p>
     */
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

    /**
     * 检查主题资源路径是否是无逃逸的正斜杠相对路径。
     *
     * <p>拒绝空路径、绝对路径、反斜杠、百分号、冒号、空段、点段和父目录段。该检查
     * 只处理字符串形状，扩展名白名单与真实文件检查由调用方继续执行。</p>
     */
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

    /** 按白名单扩展名返回浏览器内容类型；调用方保证路径已通过类型白名单。 */
    private String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.get(extension);
    }

    /** 读取必填文本字段并去除首尾空白；缺失或空白时拒绝主题包。 */
    private String requiredText(JsonNode root, String field, String id) throws ThemeLoadFailure {
        String value = optionalText(root, field, id, "");
        if (value.isBlank()) {
            throw ThemeLoadFailure.of(id, "INVALID_THEME_MANIFEST", "主题缺少文本字段：" + field);
        }
        return value.trim();
    }

    /** 读取可选文本字段；字段存在但不是 JSON 文本时拒绝主题包。 */
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

    /** 读取必填非负整数；缺失、非整数、超出 int 或负数都拒绝主题包。 */
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

    /** 读取可选布尔字段；字段存在但不是 JSON 布尔值时拒绝主题包。 */
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
