package com.orderly.host.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 从源主题目录或 classpath 回退加载主题，不依赖旧 static 产物。 */
@Component
public class ThemeCatalog {

    private static final Pattern THEME_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern TOKEN_NAME = Pattern.compile("--[a-z0-9-]+");
    private static final String CLASSPATH_PATTERN = "classpath*:themes/*.json";

    private final List<ThemeDefinition> themes;
    private final Map<String, ThemeDefinition> themesById;

    public ThemeCatalog(
            ObjectMapper objectMapper,
            @Value("${orderly.themes-dir:themes}") String themesDirectory
    ) {
        Map<String, ThemeDefinition> loaded = new LinkedHashMap<>();
        Path sourceDirectory = Path.of(themesDirectory).toAbsolutePath().normalize();

        if (Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(sourceDirectory)
                    || !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("主题源路径必须是普通目录：" + sourceDirectory);
            }
            loadSourceDirectory(objectMapper, sourceDirectory, loaded);
        } else {
            loadClasspathFallback(objectMapper, loaded);
        }

        long defaults = loaded.values().stream().filter(ThemeDefinition::defaultTheme).count();
        if (defaults > 1) {
            throw new IllegalStateException("主题目录只能声明一个 default: true");
        }

        this.themes = loaded.values().stream()
                .sorted(Comparator.comparingInt(ThemeDefinition::order)
                        .thenComparing(ThemeDefinition::id))
                .toList();
        this.themesById = Map.copyOf(loaded);
    }

    public List<ThemeSummary> summaries() {
        return themes.stream().map(ThemeDefinition::summary).toList();
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

    private void loadSourceDirectory(
            ObjectMapper objectMapper,
            Path directory,
            Map<String, ThemeDefinition> loaded
    ) {
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = entries
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("主题文件必须是普通本地文件：" + file.getFileName());
                }
                try (var stream = Files.newInputStream(file)) {
                    addTheme(parseTheme(objectMapper.readTree(stream)), loaded);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取主题源目录失败：" + directory, exception);
        }
    }

    private void loadClasspathFallback(ObjectMapper objectMapper, Map<String, ThemeDefinition> loaded) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(CLASSPATH_PATTERN);
            List<Resource> ordered = new ArrayList<>(List.of(resources));
            ordered.sort(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
            for (Resource resource : ordered) {
                try (var stream = resource.getInputStream()) {
                    addTheme(parseTheme(objectMapper.readTree(stream)), loaded);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 classpath 主题回退失败", exception);
        }
    }

    private void addTheme(ThemeDefinition theme, Map<String, ThemeDefinition> loaded) {
        if (loaded.putIfAbsent(theme.id(), theme) != null) {
            throw new IllegalStateException("检测到重复主题 id：" + theme.id());
        }
    }

    private ThemeDefinition parseTheme(JsonNode root) {
        String id = requiredText(root, "id");
        if (!THEME_ID.matcher(id).matches()) {
            throw new IllegalStateException("主题 id 非法：" + id);
        }
        String label = requiredText(root, "label");
        String note = root.path("note").asText("");
        if (!root.path("order").canConvertToInt()) {
            throw new IllegalStateException("主题缺少整数 order：" + id);
        }
        int order = root.path("order").asInt();
        if (order < 0) {
            throw new IllegalStateException("主题 order 不能为负数：" + id);
        }
        boolean isDefault = root.path("default").asBoolean(false);
        String colorScheme = root.path("colorScheme").asText("");
        if (!colorScheme.isBlank() && !colorScheme.equals("light") && !colorScheme.equals("dark")) {
            throw new IllegalStateException("主题 colorScheme 非法：" + id);
        }
        String browserColor = root.path("browserColor").asText("");
        JsonNode rawTokens = root.path("tokens");
        if (!rawTokens.isObject()) {
            throw new IllegalStateException("主题 tokens 必须是对象：" + id);
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        rawTokens.fields().forEachRemaining(entry -> {
            if (!TOKEN_NAME.matcher(entry.getKey()).matches() || !entry.getValue().isTextual()) {
                throw new IllegalStateException("主题令牌非法：" + id + "/" + entry.getKey());
            }
            tokens.put(entry.getKey(), entry.getValue().asText());
        });
        return new ThemeDefinition(id, label, note, order, isDefault, colorScheme, browserColor, tokens);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalStateException("主题缺少文本字段：" + field);
        }
        return value;
    }
}