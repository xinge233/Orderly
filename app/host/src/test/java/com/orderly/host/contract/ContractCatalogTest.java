package com.orderly.host.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 外部 JAR 发现、严格契约绑定与重复声明失败路径。 */
class ContractCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingModuleDirectoryIsAnEmptyCatalog() throws Exception {
        Path missing = Path.of("target", "missing-modules-" + UUID.randomUUID()).toAbsolutePath();
        try (ContractCatalog catalog = new ContractCatalog(objectMapper, missing.toString())) {
            assertThat(catalog.modules()).isEmpty();
            assertThatThrownBy(() -> catalog.requireExecutor("module_fs"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void jarNameMustMatchContractModuleId() throws Exception {
        Path directory = testDirectory("module-id-mismatch");
        Path source = stagedJar("module_todo.jar");
        Files.copy(source, directory.resolve("a.jar"));

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAR 名和契约 moduleId 必须一致");
    }

    @Test
    void duplicateActionsFailAtStartup() throws Exception {
        Path directory = testDirectory("duplicate-action");
        rewriteStagedJar(directory.resolve("module_todo.jar"), "module_todo.json", root -> {
            ArrayNode actions = (ArrayNode) root.path("actions");
            actions.add(actions.get(0).deepCopy());
        });

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复动作契约");
    }

    @Test
    void duplicateQueriesFailAtStartup() throws Exception {
        Path directory = testDirectory("duplicate-query");
        rewriteStagedJar(directory.resolve("module_todo.jar"), "module_todo.json", root -> {
            ArrayNode queries = (ArrayNode) root.path("queries");
            queries.add(queries.get(0).deepCopy());
        });

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复查询契约");
    }

    @Test
    void plannerRuleMustReferenceOwnedAction() throws Exception {
        Path directory = testDirectory("planner-rule");
        rewriteStagedJar(directory.resolve("module_todo.jar"), "module_todo.json", root ->
                ((ObjectNode) root.path("plannerRules").get(0)).put("actionId", "fs.mkdir"));

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本模块不存在的动作");
    }

    @Test
    void contractWithoutMatchingExecutorFailsAtStartup() throws Exception {
        Path directory = testDirectory("contract-mismatch");
        rewriteStagedJar(directory.resolve("module_without_executor.jar"), "module_todo.json", root ->
                root.put("moduleId", "module_without_executor"));

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("契约与执行器不一致");
    }

    @Test
    void declaredModuleIconAssetsAreServedFromTheModuleJar() throws Exception {
        Path directory = testDirectory("module-local-icon");
        rewriteStagedJar(
                directory.resolve("module_todo.jar"),
                "orderly-ui/module.json",
                root -> root.set(
                        "icons",
                        objectMapper.createObjectNode().put("todo-local", "icons/todo-local.svg")
                ),
                Map.of(
                        "orderly-ui/icons/todo-local.svg",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"></svg>"
                                .getBytes(StandardCharsets.UTF_8)
                )
        );

        try (ContractCatalog catalog = new ContractCatalog(objectMapper, directory.toString())) {
            ModuleUiAsset asset = catalog.readUiAsset("module_todo", "icons/todo-local.svg")
                    .orElseThrow();
            assertThat(asset.contentType()).isEqualTo("image/svg+xml");
            assertThat(asset.content()).startsWith("<svg".getBytes(StandardCharsets.UTF_8));
            assertThat(catalog.readUiAsset("module_todo", "icons/undeclared.svg")).isEmpty();
        }
    }

    @Test
    void declaredButMissingModuleIconFailsAtStartup() throws Exception {
        Path directory = testDirectory("missing-module-icon");
        rewriteStagedJar(
                directory.resolve("module_todo.jar"),
                "orderly-ui/module.json",
                root -> root.set(
                        "icons",
                        objectMapper.createObjectNode().put("todo-local", "icons/todo-local.svg")
                )
        );

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("图标资源不存在");
    }

    private Path stagedJar(String fileName) {
        String configured = System.getProperty("orderly.modules-dir");
        assertThat(configured).as("gate/build.ps1 必须先构建外部模块 JAR").isNotBlank();
        Path jar = Path.of(configured).resolve(fileName).toAbsolutePath().normalize();
        assertThat(jar).as("外部模块 JAR 必须存在").isRegularFile();
        return jar;
    }

    private Path testDirectory(String prefix) throws IOException {
        Path directory = Path.of("target", "contract-catalog-" + prefix + "-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory;
    }

    private void rewriteStagedJar(
            Path destination,
            String contractFileName,
            Consumer<ObjectNode> mutation
    ) throws IOException {
        rewriteStagedJar(destination, contractFileName, mutation, Map.of());
    }

    private void rewriteStagedJar(
            Path destination,
            String jsonEntrySuffix,
            Consumer<ObjectNode> mutation,
            Map<String, byte[]> additionalEntries
    ) throws IOException {
        Path source = stagedJar("module_todo.jar");
        try (JarFile input = new JarFile(source.toFile(), false);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
            Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (InputStream stream = input.getInputStream(entry)) {
                        byte[] bytes = stream.readAllBytes();
                        if (entry.getName().equals(jsonEntrySuffix)
                                || entry.getName().endsWith("/" + jsonEntrySuffix)) {
                            ObjectNode root = (ObjectNode) objectMapper.readTree(bytes);
                            mutation.accept(root);
                            bytes = objectMapper.writeValueAsBytes(root);
                        }
                        output.write(bytes);
                    }
                }
                output.closeEntry();
            }
            for (Map.Entry<String, byte[]> addition : additionalEntries.entrySet()) {
                output.putNextEntry(new JarEntry(addition.getKey()));
                output.write(addition.getValue());
                output.closeEntry();
            }
        }
    }
}
