package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.modulecontrol.ModulePackageInstaller.ModulePackageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModulePackageInstallerTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void stagesAndCommitsAValidBusinessModulePackage() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        byte[] archive = moduleArchive(
                "module_todo",
                contract("module_todo", false)
        );

        Path staged = installer.stage(
                archive,
                "module_todo",
                sha256(archive),
                archive.length,
                tempDirectory.resolve("staging")
        );
        Path sourceRoot = tempDirectory.resolve("Module");
        Path committed = installer.commit(staged, sourceRoot);

        assertThat(committed).isEqualTo(sourceRoot.resolve("module_todo"));
        assertThat(Files.readString(committed.resolve(
                "src/main/resources/orderly-modules/module_todo.json"
        ))).contains("\"moduleId\":\"module_todo\"");
    }

    @Test
    void cleansOnlyTheTemporaryStagingDirectory() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        byte[] archive = moduleArchive(
                "module_todo",
                contract("module_todo", false)
        );
        Path staging = tempDirectory.resolve("staging");

        Path staged = installer.stage(
                archive,
                "module_todo",
                sha256(archive),
                archive.length,
                staging
        );
        Path committed = installer.commit(staged, tempDirectory.resolve("Module"));

        installer.cleanup(staging);

        assertThat(staging).doesNotExist();
        assertThat(committed.resolve("pom.xml")).isRegularFile();
    }

    @Test
    void rejectsSystemModuleIdentityBeforeExtraction() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        byte[] archive = moduleArchive(
                "module_system_control",
                contract("module_system_control", true)
        );

        assertThatThrownBy(() -> installer.stage(
                archive,
                "module_system_control",
                sha256(archive),
                archive.length,
                tempDirectory.resolve("system")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("受保护系统模块");
    }

    @Test
    void rejectsPathTraversalAndContractSystemMetadata() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        byte[] traversal = archive(Map.of(
                "module_todo/pom.xml", "<project/>",
                "module_todo/../escape.txt", "bad"
        ));
        assertThatThrownBy(() -> installer.stage(
                traversal,
                "module_todo",
                sha256(traversal),
                traversal.length,
                tempDirectory.resolve("traversal")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("非法路径");

        byte[] systemMetadata = moduleArchive(
                "module_todo",
                contract("module_todo", true)
        );
        assertThatThrownBy(() -> installer.stage(
                systemMetadata,
                "module_todo",
                sha256(systemMetadata),
                systemMetadata.length,
                tempDirectory.resolve("system-metadata")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("系统模块元数据");
    }

    @Test
    void rejectsHashAndSizeMismatch() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        byte[] archive = moduleArchive(
                "module_todo",
                contract("module_todo", false)
        );

        assertThatThrownBy(() -> installer.stage(
                archive,
                "module_todo",
                "0".repeat(64),
                archive.length,
                tempDirectory.resolve("hash")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("SHA-256");

        assertThatThrownBy(() -> installer.stage(
                archive,
                "module_todo",
                sha256(archive),
                archive.length + 1,
                tempDirectory.resolve("size")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("大小");
    }

    @Test
    void rejectsIncompleteContractAndUiBeforeCommit() throws Exception {
        ModulePackageInstaller installer = newInstaller();
        Map<String, String> invalidContractEntries = moduleEntries(
                "module_todo",
                """
                {"moduleId":"module_todo","title":"演示","description":"演示模块","order":1,
                "actions":[{"id":"todo.create","title":"创建","description":"创建","risk":"MAYBE",
                "confirmationRequired":true,"parameters":[]}],"queries":[],"plannerRules":[]}
                """
        );
        invalidContractEntries.remove(
                "module_todo/src/main/resources/orderly-ui/module.json"
        );
        invalidContractEntries.put(
                "module_todo/src/main/resources/orderly-ui/module.json",
                """
                {"version":1,"view":{"id":"todo","caption":"待办","icon":"modules"},"examples":[],"script":"module.js","style":"module.css"}
                """
        );
        byte[] invalidContract = archive(invalidContractEntries);
        assertThatThrownBy(() -> installer.stage(
                invalidContract,
                "module_todo",
                sha256(invalidContract),
                invalidContract.length,
                tempDirectory.resolve("invalid-contract")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("风险等级");

        Map<String, String> invalidUiEntries = moduleEntries(
                "module_todo",
                contract("module_todo", false)
        );
        invalidUiEntries.put(
                "module_todo/src/main/resources/orderly-ui/module.json",
                """
                {"version":1,"view":{"id":"todo","caption":"待办","icon":"modules"},"script":"module.js","style":"module.css"}
                """
        );
        byte[] invalidUi = archive(invalidUiEntries);
        assertThatThrownBy(() -> installer.stage(
                invalidUi,
                "module_todo",
                sha256(invalidUi),
                invalidUi.length,
                tempDirectory.resolve("invalid-ui")
        ))
                .isInstanceOf(ModulePackageException.class)
                .hasMessageContaining("examples");
    }

    private byte[] moduleArchive(String moduleId, String contract) throws Exception {
        return archive(moduleEntries(moduleId, contract));
    }

    private Map<String, String> moduleEntries(String moduleId, String contract) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(moduleId + "/pom.xml", "<project/>");
        entries.put(
                moduleId + "/src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule",
                "com.orderly.module.demo.DemoModule"
        );
        entries.put(
                moduleId + "/src/main/resources/orderly-modules/" + moduleId + ".json",
                contract
        );
        entries.put(
                moduleId + "/src/main/resources/orderly-ui/module.json",
                """
                {"version":1,"view":{"id":"demo","caption":"演示","icon":"modules"},"examples":[],"script":"module.js","style":"module.css"}
                """
        );
        entries.put(moduleId + "/src/main/resources/orderly-ui/module.js", "export default {};");
        entries.put(moduleId + "/src/main/resources/orderly-ui/module.css", ".demo{}");
        return entries;
    }

    private byte[] archive(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String contract(String moduleId, boolean system) {
        String systemMetadata = system
                ? """
                  ,"system":{"role":"MODULE_CONTROL","protected":true,"capabilities":["MODULE_GOVERNANCE"]}
                  """
                : "";
        return """
                {"moduleId":"%s","title":"演示","description":"演示模块","order":1,
                "actions":[],"queries":[],"plannerRules":[]%s}
                """.formatted(moduleId, systemMetadata);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    private ModulePackageInstaller newInstaller() {
        ContractCatalog catalog = new ContractCatalog(
                objectMapper,
                tempDirectory.resolve("catalog-" + System.nanoTime()).toString()
        );
        return new ModulePackageInstaller(catalog, objectMapper);
    }
}
