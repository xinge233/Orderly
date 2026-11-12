package com.orderly.host.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 目录主题发现、局部失败、资源白名单和契约样例的运行时校验。 */
class ThemeCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void discoversOnlyDirectDirectoryPackagesAndSortsStably() throws Exception {
        Path root = directory("stable");
        createTheme(root, "beta", 20, false, false);
        createTheme(root, "alpha", 10, true, true);
        Files.writeString(root.resolve("default.json"), "{}");
        Files.createDirectories(root.resolve("_legacy-flat-json"));

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        assertThat(catalog.snapshot().items()).extracting(ThemeSummary::id)
                .containsExactly("alpha", "beta");
        assertThat(catalog.snapshot().items()).allMatch(item -> item.schemaVersion() == 1);
        assertThat(catalog.snapshot().builtInFallbackActive()).isFalse();
        ThemeDefinition alpha = catalog.require("alpha");
        assertThat(alpha.styles()).containsExactly("tokens.css", "motion.css");
        assertThat(alpha.assets()).containsExactly("assets/sample.webp");
        JsonNode serialized = objectMapper.valueToTree(alpha);
        assertThat(serialized.has("rootPath")).isFalse();
        assertThat(serialized.path("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    void missingAndEmptyDirectoriesProduceZeroThemes() throws Exception {
        Path missing = Path.of("target", "theme-catalog-missing-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        ThemeCatalog missingCatalog = new ThemeCatalog(objectMapper, missing.toString());
        assertThat(missingCatalog.summaries()).isEmpty();
        assertThat(missingCatalog.snapshot().builtInFallbackActive()).isTrue();

        Path empty = directory("empty");
        ThemeCatalog emptyCatalog = new ThemeCatalog(objectMapper, empty.toString());
        assertThat(emptyCatalog.summaries()).isEmpty();
        assertThat(emptyCatalog.snapshot().builtInFallbackActive()).isTrue();
    }

    @Test
    void unsupportedVersionsAndBrokenManifestsStayLocal() throws Exception {
        Path root = directory("local-issues");
        createTheme(root, "good", 10, true, false);
        Path future = Files.createDirectories(root.resolve("future"));
        Files.writeString(future.resolve("theme.json"), """
                {
                  "schemaVersion": 2,
                  "id": "future",
                  "label": "未来主题",
                  "order": 20,
                  "styles": ["tokens.css", "motion.css"]
                }
                """);
        writeStyles(future, false);
        Path broken = Files.createDirectories(root.resolve("broken"));
        Files.writeString(broken.resolve("theme.json"), "{");

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        assertThat(catalog.summaries()).extracting(ThemeSummary::id).containsExactly("good");
        assertThat(catalog.snapshot().issues()).extracting(ThemeIssue::code)
                .contains("UNSUPPORTED_SCHEMA_VERSION", "INVALID_THEME_MANIFEST");
        ThemeIssue unsupported = catalog.snapshot().issues().stream()
                .filter(issue -> issue.code().equals("UNSUPPORTED_SCHEMA_VERSION"))
                .findFirst()
                .orElseThrow();
        assertThat(unsupported.actualVersion()).isEqualTo(2);
        assertThat(unsupported.supportedVersions()).containsExactly(1);
    }

    @Test
    void duplicateDefaultsRemainDirectoryLevelBlockingConflict() throws Exception {
        Path root = directory("multiple-defaults");
        createTheme(root, "alpha", 10, true, false);
        createTheme(root, "beta", 20, true, false);

        assertThatThrownBy(() -> new ThemeCatalog(objectMapper, root.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default");
    }

    @Test
    void missingStylesUnsafePathsAndNonCssStylesAreLocalIssues() throws Exception {
        Path root = directory("invalid-resources");
        createTheme(root, "good", 10, true, false);

        Path missing = Files.createDirectories(root.resolve("missing"));
        writeManifest(missing, "missing", "缺失样式", 20, false, List.of("tokens.css", "motion.css"), List.of());
        Files.writeString(missing.resolve("tokens.css"), "@layer orderly.theme {}");

        Path escaped = Files.createDirectories(root.resolve("escaped"));
        writeManifest(
                escaped,
                "escaped",
                "越界样式",
                30,
                false,
                List.of("tokens.css", "motion.css", "../outside.css"),
                List.of()
        );
        writeStyles(escaped, false);

        Path nonCss = Files.createDirectories(root.resolve("noncss"));
        writeManifest(
                nonCss,
                "noncss",
                "非 CSS",
                40,
                false,
                List.of("tokens.css", "motion.css", "theme.js"),
                List.of()
        );
        writeStyles(nonCss, false);

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        assertThat(catalog.summaries()).extracting(ThemeSummary::id).containsExactly("good");
        assertThat(catalog.snapshot().issues()).extracting(ThemeIssue::code)
                .contains("THEME_STYLE_UNREADABLE", "INVALID_RESOURCE_PATH", "INVALID_STYLE_PATH");
    }

    @Test
    void invalidDirectoryAndManifestIdsAreIsolated() throws Exception {
        Path root = directory("invalid-ids");
        createTheme(root, "good", 10, true, false);
        Path invalidDirectory = Files.createDirectories(root.resolve("Invalid Directory"));
        writeManifest(
                invalidDirectory,
                "invalid",
                "非法目录",
                20,
                false,
                List.of("tokens.css", "motion.css"),
                List.of()
        );
        writeStyles(invalidDirectory, false);
        Path mismatch = Files.createDirectories(root.resolve("mismatch"));
        writeManifest(
                mismatch,
                "different",
                "目录不一致",
                30,
                false,
                List.of("tokens.css", "motion.css"),
                List.of()
        );
        writeStyles(mismatch, false);

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        assertThat(catalog.snapshot().issues()).extracting(ThemeIssue::code)
                .contains("INVALID_THEME_ID", "THEME_ID_DIRECTORY_MISMATCH");
    }

    @Test
    void symlinkedResourcesAreRejectedWhenThePlatformAllowsCreatingThem() throws Exception {
        Path root = directory("symlink");
        Path theme = Files.createDirectories(root.resolve("linked"));
        Path external = directory("symlink-external").resolve("outside.css");
        Files.writeString(external, "@layer orderly.theme {}");
        writeManifest(
                theme,
                "linked",
                "链接样式",
                10,
                false,
                List.of("tokens.css", "motion.css", "linked.css"),
                List.of()
        );
        writeStyles(theme, false);
        try {
            Files.createSymbolicLink(theme.resolve("linked.css"), external);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接测试资源");
            return;
        }

        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        assertThat(catalog.summaries()).isEmpty();
        assertThat(catalog.snapshot().issues()).extracting(ThemeIssue::code)
                .contains("THEME_PATH_UNSAFE");
    }

    @Test
    void registeredRasterAssetsReturnMimeTypesAndSvgIsNeverRegistered() throws Exception {
        Path root = directory("assets");
        createTheme(root, "alpha", 10, true, true);
        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());

        ThemeAsset css = catalog.readAsset("alpha", "tokens.css").orElseThrow();
        ThemeAsset webp = catalog.readAsset("alpha", "assets/sample.webp").orElseThrow();

        assertThat(css.contentType()).isEqualTo("text/css");
        assertThat(new String(css.content())).contains("@layer orderly.theme");
        assertThat(webp.contentType()).isEqualTo("image/webp");
        assertThat(catalog.readAsset("alpha", "theme.json")).isEmpty();
        assertThat(catalog.readAsset("alpha", "../theme.json")).isEmpty();
        assertThat(catalog.readAsset("alpha", "assets%2Fsample.webp")).isEmpty();
        assertThat(catalog.readAsset("alpha", "assets\\sample.webp")).isEmpty();
        assertThat(catalog.readAsset("alpha", "assets/extra.webp")).isEmpty();
        assertThat(catalog.readAsset("alpha", "assets/sample.svg")).isEmpty();
    }

    @Test
    void contractSamplesTrackRuntimePolicy() throws Exception {
        Path samples = Path.of(System.getProperty("orderly.contracts-samples-dir"))
                .toAbsolutePath()
                .normalize();
        Path validRoot = directory("contract-valid");
        Path sampleTheme = Files.createDirectories(validRoot.resolve("sample"));
        Files.copy(samples.resolve("valid/theme.json"), sampleTheme.resolve("theme.json"));
        writeStyles(sampleTheme, true);

        ThemeCatalog validCatalog = new ThemeCatalog(objectMapper, validRoot.toString());
        assertThat(validCatalog.snapshot().issues()).isEmpty();
        assertThat(validCatalog.summaries()).extracting(ThemeSummary::id).containsExactly("sample");

        assertInvalidSample(
                samples.resolve("invalid/theme-unsupported-version.json"),
                "UNSUPPORTED_SCHEMA_VERSION"
        );
        assertInvalidSample(samples.resolve("invalid/theme-invalid-id.json"), "INVALID_THEME_ID");
        assertInvalidSample(
                samples.resolve("invalid/theme-style-path-escape.json"),
                "INVALID_RESOURCE_PATH"
        );
        assertInvalidSample(samples.resolve("invalid/theme-style-non-css.json"), "INVALID_STYLE_PATH");
        assertInvalidSample(
                samples.resolve("invalid/theme-active-svg-asset.json"),
                "INVALID_ASSET_PATH"
        );
    }

    private void assertInvalidSample(Path sample, String expectedCode) throws Exception {
        Path root = directory("contract-" + sample.getFileName());
        JsonNode manifest = objectMapper.readTree(Files.readString(sample));
        String declaredId = manifest.path("id").asText("candidate");
        String directoryName = declaredId.matches("[a-z][a-z0-9-]{0,63}")
                ? declaredId
                : "candidate";
        Path theme = Files.createDirectories(root.resolve(directoryName));
        Files.copy(sample, theme.resolve("theme.json"));
        writeStyles(theme, false);
        ThemeCatalog catalog = new ThemeCatalog(objectMapper, root.toString());
        assertThat(catalog.snapshot().issues()).extracting(ThemeIssue::code).contains(expectedCode);
    }

    private Path directory(String name) throws IOException {
        Path directory = Path.of("target", "theme-catalog-" + name + "-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory;
    }

    private void createTheme(
            Path root,
            String id,
            int order,
            boolean isDefault,
            boolean withAsset
    ) throws IOException {
        Path theme = Files.createDirectories(root.resolve(id));
        List<String> assets = withAsset ? List.of("assets/sample.webp") : List.of();
        writeManifest(
                theme,
                id,
                id + " 主题",
                order,
                isDefault,
                List.of("tokens.css", "motion.css"),
                assets
        );
        writeStyles(theme, withAsset);
    }

    private void writeManifest(
            Path theme,
            String id,
            String label,
            int order,
            boolean isDefault,
            List<String> styles,
            List<String> assets
    ) throws IOException {
        String stylesJson = styles.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        String assetsJson = assets.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        Files.writeString(theme.resolve("theme.json"), """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "label": "%s",
                  "note": "测试主题",
                  "order": %d,
                  "default": %s,
                  "colorScheme": "light",
                  "browserColor": "Canvas",
                  "styles": [%s],
                  "assets": [%s]
                }
                """.formatted(id, label, order, isDefault, stylesJson, assetsJson));
    }

    private void writeStyles(Path theme, boolean withAsset) throws IOException {
        Files.writeString(theme.resolve("tokens.css"), """
                @layer orderly.theme {
                    html[data-theme="%s"] { --color-accent: LinkText; }
                }
                """.formatted(theme.getFileName()));
        Files.writeString(theme.resolve("motion.css"), "@layer orderly.theme {}");
        if (withAsset) {
            Path assets = Files.createDirectories(theme.resolve("assets"));
            writeRaster(assets.resolve("sample.webp"));
            writeRaster(assets.resolve("sample-grain.webp"));
        }
    }

    private void writeRaster(Path path) throws IOException {
        Files.write(path, new byte[]{
                'R', 'I', 'F', 'F',
                4, 0, 0, 0,
                'W', 'E', 'B', 'P'
        });
    }
}
