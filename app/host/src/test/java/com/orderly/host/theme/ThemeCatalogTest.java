package com.orderly.host.theme;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 主题源目录优先级、稳定摘要和不合法声明失败路径。 */
class ThemeCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sourceThemesAreStableAndSourceDirectoryWins() throws Exception {
        Path directory = directory("stable");
        writeTheme(directory.resolve("b.json"), "beta", "乙", 20, false, "light");
        writeTheme(directory.resolve("a.json"), "alpha", "甲", 10, true, "dark");

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, directory.toString());

        assertThat(catalog.summaries()).extracting(ThemeSummary::id).containsExactly("alpha", "beta");
        assertThat(catalog.summaries().getFirst().defaultTheme()).isTrue();
        assertThat(catalog.require("alpha").tokens()).containsEntry("--color-accent", "red");
        assertThat(catalog.require("alpha").colorScheme()).isEqualTo("dark");
    }

    @Test
    void existingEmptySourceDirectoryReturnsNoThemesInsteadOfClasspathStaleData() throws Exception {
        Path directory = directory("empty");
        ThemeCatalog catalog = new ThemeCatalog(objectMapper, directory.toString());
        assertThat(catalog.summaries()).isEmpty();
    }

    @Test
    void duplicateAndInvalidIdsAreRejected() throws Exception {
        Path duplicate = directory("duplicate");
        writeTheme(duplicate.resolve("a.json"), "same", "甲", 10, true, "light");
        writeTheme(duplicate.resolve("b.json"), "same", "乙", 20, false, "dark");
        assertThatThrownBy(() -> new ThemeCatalog(objectMapper, duplicate.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复主题 id");

        Path invalid = directory("invalid");
        writeTheme(invalid.resolve("a.json"), "Invalid Id", "非法", 10, true, "light");
        assertThatThrownBy(() -> new ThemeCatalog(objectMapper, invalid.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主题 id 非法");
    }

    private Path directory(String name) throws IOException {
        Path directory = Path.of("target", "theme-catalog-" + name + "-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory;
    }

    private void writeTheme(
            Path path,
            String id,
            String label,
            int order,
            boolean isDefault,
            String scheme
    ) throws IOException {
        String json = """
                {
                  "id": "%s",
                  "label": "%s",
                  "note": "测试主题",
                  "order": %d,
                  "default": %s,
                  "colorScheme": "%s",
                  "browserColor": "Canvas",
                  "tokens": {
                    "--color-accent": "red"
                  }
                }
                """.formatted(id, label, order, isDefault, scheme);
        Files.writeString(path, json);
    }
}