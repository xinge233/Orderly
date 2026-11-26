package com.orderly.host.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodegenServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rollsBackSelectedStepAndAllLaterSteps() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "",
                  "summary": "change theme motion",
                  "steps": [
                    {
                      "title": "motion",
                      "path": "motion.css",
                      "content": ".new-motion { transition: none; }"
                    },
                    {
                      "title": "tokens",
                      "path": "tokens.css",
                      "content": ":root { --codegen-test: 1; }"
                    }
                  ]
                }
                """));
        CodegenPreviewService preview = mock(CodegenPreviewService.class);
        CodegenService service = service(context, runner, preview);

        CodegenService.SessionView generated = service.generate(
                request("motion", "framework", "paper")
        );
        awaitBuild(service, generated.sessionId());

        CodegenService.SessionView rolledBack = service.rollbackStep(
                generated.sessionId(),
                generated.steps().getFirst().stepId()
        );

        assertThat(rolledBack.steps())
                .extracting(CodegenService.StepView::status)
                .containsOnly("ROLLED_BACK");
        Path workspace = tempDir.resolve(".orderly-data/codegen/workspaces")
                .resolve(generated.sessionId())
                .resolve("source");
        assertThat(Files.readString(
                workspace.resolve("motion.css")
        )).contains("original-motion");
        assertThat(Files.readString(
                workspace.resolve("tokens.css")
        )).contains("original-token");
    }

    @Test
    void appliesReviewedThemeVersionAndMarksItPendingRestart() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "",
                  "summary": "change motion",
                  "steps": [{
                    "title": "motion",
                    "path": "motion.css",
                    "content": ".candidate { opacity: 1; }"
                  }]
                }
                """));
        CodegenPreviewService preview = mock(CodegenPreviewService.class);
        when(preview.start(any(), any(), any(), any())).thenReturn(
                new CodegenPreviewService.PreviewHandle(
                        "http://127.0.0.1:18081/",
                        18081,
                        1
                )
        );
        CodegenService service = service(context, runner, preview);

        CodegenService.SessionView generated = service.generate(
                request("motion", "framework", "paper")
        );
        awaitBuild(service, generated.sessionId());
        service.preview(generated.sessionId());
        CodegenService.SessionView applied = service.apply(generated.sessionId());

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.applyVersionId()).isNotBlank();
        assertThat(applied.previewStatus()).isEqualTo("STOPPED");
        assertThat(service.versions()).singleElement()
                .extracting(CodegenService.CodegenVersion::status)
                .isEqualTo("PENDING_RESTART");
        assertThat(Files.readString(
                tempDir.resolve("themes/paper/motion.css")
        )).contains(".candidate");
        assertThat(tempDir.resolve(".orderly-data/trash/codegen")).exists();
    }

    @Test
    void rollbackMovesNewFilesIntoProjectTrash() throws Exception {
        TestContext context = contextWithoutTokens();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "",
                  "summary": "add tokens",
                  "steps": [{
                    "title": "tokens",
                    "path": "tokens.css",
                    "content": ":root { --candidate-token: 1; }"
                  }]
                }
                """));
        CodegenService service = service(
                context,
                runner,
                mock(CodegenPreviewService.class)
        );

        CodegenService.SessionView generated = service.generate(
                request("motion", "framework", "paper")
        );
        awaitBuild(service, generated.sessionId());
        CodegenService.SessionView rolledBack = service.rollbackStep(
                generated.sessionId(),
                generated.steps().getFirst().stepId()
        );

        assertThat(rolledBack.status()).isEqualTo("ROLLED_BACK");
        Path workspace = tempDir.resolve(".orderly-data/codegen/workspaces")
                .resolve(generated.sessionId())
                .resolve("source");
        assertThat(workspace.resolve("tokens.css")).doesNotExist();
        Path trash = tempDir.resolve(".orderly-data/trash/codegen")
                .resolve(generated.sessionId());
        assertThat(trash).exists();
        try (var files = Files.walk(trash)) {
            assertThat(files
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList())
                    .anySatisfy(content ->
                            assertThat(content).contains("--candidate-token"));
        }
    }

    @Test
    void resetsPersistedPreviewStateWhenHostRestarts() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "",
                  "summary": "change motion",
                  "steps": [{
                    "title": "motion",
                    "path": "motion.css",
                    "content": ".candidate { opacity: 1; }"
                  }]
                }
                """));
        CodegenPreviewService preview = mock(CodegenPreviewService.class);
        when(preview.start(any(), any(), any(), any())).thenReturn(
                new CodegenPreviewService.PreviewHandle(
                        "http://127.0.0.1:18081/",
                        18081,
                        1
                )
        );
        CodegenService first = service(context, runner, preview);
        CodegenService.SessionView generated = first.generate(
                request("motion", "framework", "paper")
        );
        first.preview(generated.sessionId());
        assertThat(first.session(generated.sessionId()).previewStatus()).isEqualTo("READY");
        first.shutdown();

        CodegenService restarted = service(
                context,
                runner,
                mock(CodegenPreviewService.class)
        );
        CodegenService.SessionView restored = restarted.session(generated.sessionId());

        assertThat(restored.previewStatus()).isEqualTo("STOPPED");
        assertThat(restored.previewUrl()).isEmpty();
        restarted.shutdown();
    }

    @Test
    void rejectsIncompleteNewModuleProposalBeforeBuild() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "module_candidate",
                  "summary": "incomplete",
                  "steps": [{
                    "title": "pom",
                    "path": "pom.xml",
                    "content": "<project/>"
                  }]
                }
                """));
        CodegenService service = service(
                context,
                runner,
                mock(CodegenPreviewService.class)
        );

        assertThatThrownBy(() -> service.generate(new CodegenService.GenerateRequest(
                "module",
                "create a module",
                "",
                "",
                "paper",
                List.of(),
                List.of()
        )))
                .isInstanceOf(com.orderly.host.api.ApiException.class)
                .hasMessageContaining("缺少必需文件");
        assertThat(tempDir.resolve("Module/module_candidate")).doesNotExist();
    }

    @Test
    void rejectsTraversalInGeneratedModulePaths() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "module_candidate",
                  "summary": "unsafe",
                  "steps": [{
                    "title": "escape",
                    "path": "../escape.java",
                    "content": "class Escape {}"
                  }]
                }
                """));
        CodegenService service = service(
                context,
                runner,
                mock(CodegenPreviewService.class)
        );

        assertThatThrownBy(() -> service.generate(new CodegenService.GenerateRequest(
                "module",
                "create a module",
                "",
                "",
                "paper",
                List.of(),
                List.of()
        )))
                .isInstanceOf(com.orderly.host.api.ApiException.class)
                .hasMessageContaining("未规范化的文件路径");
    }

    @Test
    void acceptsRequiredModuleSpiDescriptorPathBeforeCompletenessCheck() throws Exception {
        TestContext context = contextWithTheme();
        CodegenProposalRunner runner = mock(CodegenProposalRunner.class);
        when(runner.propose(any())).thenReturn(new ObjectMapper().readTree("""
                {
                  "moduleId": "module_candidate",
                  "summary": "spi descriptor",
                  "steps": [{
                    "title": "spi",
                    "path": "src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule",
                    "content": "com.orderly.module.candidate.CandidateModule\\n"
                  }]
                }
                """));
        CodegenService service = service(
                context,
                runner,
                mock(CodegenPreviewService.class)
        );

        assertThatThrownBy(() -> service.generate(new CodegenService.GenerateRequest(
                "module",
                "create a module",
                "",
                "",
                "paper",
                List.of(),
                List.of()
        )))
                .isInstanceOf(com.orderly.host.api.ApiException.class)
                .hasMessageContaining("缺少必需文件")
                .hasMessageNotContaining("不支持该文本文件类型");
    }

    @Test
    void injectsMissingParentRelativePathIntoGeneratedPom() {
        String pom = """
                <project>
                  <parent>
                    <groupId>com.orderly</groupId>
                    <artifactId>orderly-parent</artifactId>
                    <version>0.1.0</version>
                  </parent>
                </project>
                """;

        String patched = CodegenService.ensureParentRelativePath(
                pom,
                "D:/work/Orderly/pom.xml",
                new CodegenService.ParentCoordinates(
                        "com.orderly",
                        "orderly-parent",
                        "0.1.0"
                )
        );

        assertThat(patched).contains(
                "<relativePath>D:/work/Orderly/pom.xml</relativePath>"
        );
        assertThat(patched.indexOf("<relativePath>"))
                .isGreaterThan(patched.indexOf("</artifactId>"));
    }

    @Test
    void replacesEmptySelfClosingParentRelativePath() {
        String pom = """
                <project>
                  <parent>
                    <groupId>com.orderly</groupId>
                    <artifactId>orderly-parent</artifactId>
                    <version>0.1.0</version>
                    <relativePath/>
                  </parent>
                </project>
                """;

        String patched = CodegenService.ensureParentRelativePath(
                pom,
                "D:/work/Orderly/pom.xml",
                new CodegenService.ParentCoordinates(
                        "com.orderly",
                        "orderly-parent",
                        "0.1.0"
                )
        );

        assertThat(patched)
                .contains("<relativePath>D:/work/Orderly/pom.xml</relativePath>")
                .doesNotContain("<relativePath/>");
    }

    @Test
    void normalizesWrongGeneratedParentCoordinates() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.orderly</groupId>
                    <artifactId>orderly-parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath/>
                  </parent>
                </project>
                """;

        String patched = CodegenService.ensureParentRelativePath(
                pom,
                "D:/work/Orderly/pom.xml",
                CodegenService.parentCoordinates("""
                        <project>
                          <parent>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-parent</artifactId>
                            <version>3.4.12</version>
                          </parent>
                          <groupId>com.orderly</groupId>
                          <artifactId>orderly-parent</artifactId>
                          <version>0.1.0</version>
                        </project>
                        """)
        );

        assertThat(patched)
                .contains("<version>0.1.0</version>")
                .doesNotContain("<version>1.0.0</version>");
    }

    @Test
    void normalizesKernelDependencyVersion() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.orderly</groupId>
                      <artifactId>orderly-kernel</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;

        String patched = CodegenService.ensureKernelDependencyVersion(pom);

        assertThat(patched).contains(
                "<version>${project.version}</version>"
        );
    }

    @Test
    void normalizesGeneratedModuleArtifactId() {
        String pom = """
                <project>
                  <parent>
                    <groupId>com.orderly</groupId>
                    <artifactId>orderly-parent</artifactId>
                    <version>0.1.0</version>
                  </parent>
                  <artifactId>human-readable-name</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.orderly</groupId>
                      <artifactId>orderly-kernel</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        String patched = CodegenService.ensureModuleArtifactId(
                pom,
                "module_candidate"
        );

        assertThat(patched).contains(
                "<artifactId>module_candidate</artifactId>"
        );
        assertThat(patched).doesNotContain(
                "<artifactId>human-readable-name</artifactId>"
        );
        assertThat(patched).contains(
                "<artifactId>orderly-kernel</artifactId>"
        );
    }

    @Test
    void sourceHashIgnoresMavenTargetDirectory() throws Exception {
        Path source = tempDir.resolve("source-hash");
        Files.createDirectories(source.resolve("src/main/java"));
        Files.writeString(
                source.resolve("src/main/java/Candidate.java"),
                "final class Candidate {}"
        );
        String beforeBuild = CodegenService.hashTree(source);

        Files.createDirectories(source.resolve("target/classes"));
        Files.writeString(
                source.resolve("target/classes/Candidate.class"),
                "compiled-output"
        );

        assertThat(CodegenService.hashTree(source)).isEqualTo(beforeBuild);
    }

    @Test
    void normalizesLegacyGeneratedUiManifestToHostContract() throws Exception {
        String normalized = CodegenService.normalizeModuleUiManifest("""
                {
                  "schemaVersion": 1,
                  "moduleId": "module_candidate",
                  "id": "candidate-view",
                  "title": "候选模块",
                  "entry": "module.js",
                  "styles": ["module.css"],
                  "views": []
                }
                """, "module_candidate", "candidate.query");

        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(normalized);
        assertThat(root.path("version").asInt()).isEqualTo(1);
        assertThat(root.path("view").path("id").asText())
                .isEqualTo("candidate-view");
        assertThat(root.path("view").path("caption").asText())
                .isEqualTo("候选模块");
        assertThat(root.path("card").path("queryId").asText())
                .isEqualTo("candidate.query");
        assertThat(root.path("script").asText()).isEqualTo("module.js");
        assertThat(root.path("style").asText()).isEqualTo("module.css");
        assertThat(root.has("schemaVersion")).isFalse();
        assertThat(root.has("entry")).isFalse();
        assertThat(root.has("styles")).isFalse();
    }

    @Test
    void rejectsGeneratedUiScriptWithoutDefaultMountExport() {
        assertThatThrownBy(() ->
                CodegenService.requireDefaultModuleExport(
                        "(function () { globalThis.OrderlyModules = {}; })();"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export default");

        assertThat(CodegenService.requireDefaultModuleExport(
                "export default async function mount(root, context) {}"
        )).contains("export default");
    }

    private TestContext contextWithTheme() throws Exception {
        return contextWithTheme(true);
    }

    private TestContext contextWithoutTokens() throws Exception {
        return contextWithTheme(false);
    }

    private TestContext contextWithTheme(boolean includeTokens) throws Exception {
        Path moduleSource = tempDir.resolve("Module");
        Path themes = tempDir.resolve("themes");
        Path modules = tempDir.resolve("target/orderly-modules");
        Files.createDirectories(moduleSource);
        Files.createDirectories(themes.resolve("paper"));
        Files.createDirectories(modules);
        Files.writeString(
                themes.resolve("paper/motion.css"),
                "/* original-motion */\n@layer orderly.theme { }\n"
        );
        if (includeTokens) {
            Files.writeString(
                    themes.resolve("paper/tokens.css"),
                    "/* original-token */\n@layer orderly.theme { }\n"
            );
        }
        Files.writeString(
                themes.resolve("paper/theme.json"),
                "{\"schemaVersion\":1,\"id\":\"paper\"}"
        );
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceRuntime runtime = new WorkspaceRuntime(
                tempDir.resolve(".orderly-data").toString(),
                mapper
        );
        runtime.initialize();
        return new TestContext(moduleSource, themes, modules, runtime, mapper);
    }

    private CodegenService service(
            TestContext context,
            CodegenProposalRunner runner,
            CodegenPreviewService preview
    ) {
        return new CodegenService(
                context.mapper(),
                context.runtime(),
                mock(ContractCatalog.class),
                mock(ExecutionJournal.class),
                runner,
                preview,
                context.moduleSource().toString(),
                context.themes().toString(),
                context.modules().toString()
        );
    }

    private CodegenService.GenerateRequest request(
            String mode,
            String motionTarget,
            String themeId
    ) {
        return new CodegenService.GenerateRequest(
                mode,
                "make motion calmer",
                "",
                motionTarget,
                themeId,
                List.of(),
                List.of(Map.of("scope", "sidebar", "state", "expanded"))
        );
    }

    private void awaitBuild(CodegenService service, String sessionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String lastStatus = "";
        while (System.nanoTime() < deadline) {
            CodegenService.SessionView current = service.session(sessionId);
            lastStatus = current.buildStatus();
            if ("READY".equals(current.buildStatus())) {
                return;
            }
            if ("FAILED".equals(current.buildStatus())) {
                throw new AssertionError(
                        "codegen build failed: " + current.buildMessage()
                );
            }
            Thread.sleep(25);
        }
        throw new AssertionError(
                "codegen build did not become ready; last status=" + lastStatus
        );
    }

    private record TestContext(
            Path moduleSource,
            Path themes,
            Path modules,
            WorkspaceRuntime runtime,
            ObjectMapper mapper
    ) {
    }
}
