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
        assertThat(service.versions()).singleElement()
                .extracting(CodegenService.CodegenVersion::status)
                .isEqualTo("PENDING_RESTART");
        assertThat(Files.readString(
                tempDir.resolve("themes/paper/motion.css")
        )).contains(".candidate");
        assertThat(tempDir.resolve(".orderly-data/trash/codegen")).exists();
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

    private TestContext contextWithTheme() throws Exception {
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
        Files.writeString(
                themes.resolve("paper/tokens.css"),
                "/* original-token */\n@layer orderly.theme { }\n"
        );
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
