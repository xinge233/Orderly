package com.orderly.host.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
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
    void duplicateModuleJarsFailAtStartup() throws Exception {
        Path directory = testDirectory("duplicate-module");
        Path source = stagedJar("module_todo.jar");
        Files.copy(source, directory.resolve("a.jar"));
        Files.copy(source, directory.resolve("b.jar"));

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复模块契约");
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
        rewriteStagedJar(directory.resolve("module_todo.jar"), "module_todo.json", root ->
                root.put("moduleId", "module_without_executor"));

        assertThatThrownBy(() -> new ContractCatalog(objectMapper, directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("契约与执行器不一致");
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
                        if (entry.getName().endsWith("/" + contractFileName)) {
                            ObjectNode root = (ObjectNode) objectMapper.readTree(bytes);
                            mutation.accept(root);
                            bytes = objectMapper.writeValueAsBytes(root);
                        }
                        output.write(bytes);
                    }
                }
                output.closeEntry();
            }
        }
    }
}
