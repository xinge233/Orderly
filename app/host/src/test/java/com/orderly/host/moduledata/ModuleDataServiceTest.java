package com.orderly.host.moduledata;

import com.orderly.host.api.ApiException;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleDataServiceTest {

    @TempDir
    Path temp;

    private ModuleDataService service;
    private WorkspaceRuntime runtime;

    @BeforeEach
    void setUp() {
        ModuleControlService modules = mock(ModuleControlService.class);
        runtime = mock(WorkspaceRuntime.class);
        service = new ModuleDataService(modules, runtime);
        when(runtime.resolveWorkspacePath(anyString())).thenAnswer(invocation ->
                temp.resolve(invocation.getArgument(0, String.class)).normalize()
        );
        when(runtime.createBinaryFile(any(Path.class), any(byte[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Path.class));
    }

    @Test
    void acceptsSupportedDocumentAndTextSignatures() {
        ModuleDataService.ModuleDataAsset pdf = service.stage(
                "module_materials",
                "uploads/guide.pdf",
                "application/pdf",
                "%PDF-1.7\ncontent".getBytes(StandardCharsets.UTF_8)
        );
        ModuleDataService.ModuleDataAsset docx = service.stage(
                "module_materials",
                "uploads/guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{'P', 'K', 3, 4, 0}
        );
        ModuleDataService.ModuleDataAsset markdown = service.stage(
                "module_materials",
                "uploads/readme.md",
                "text/markdown",
                "# 资料".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(docx.contentType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
        assertThat(markdown.contentType()).isEqualTo("text/markdown; charset=utf-8");
    }

    @Test
    void acceptsExplicitOpaqueBinaryForAuthorizedFileUpload() {
        ModuleDataService.ModuleDataAsset binary = service.stage(
                "module_fs",
                "uploads/archive.bin",
                "application/octet-stream",
                new byte[]{0, 1, 2, 3}
        );

        assertThat(binary.contentType()).isEqualTo("application/octet-stream");

        ModuleDataService.ModuleDataAsset pdf = service.stage(
                "module_fs",
                "uploads/guide.pdf",
                "application/octet-stream",
                "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(pdf.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void rejectsMimeExtensionAndSignatureMismatches() {
        assertThatThrownBy(() -> service.stage(
                "module_materials",
                "uploads/fake.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("文件内容");

        assertThatThrownBy(() -> service.stage(
                "module_materials",
                "uploads/readme.txt",
                "image/png",
                "text".getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("白名单");

        assertThatThrownBy(() -> service.stage(
                "module_materials",
                "uploads/readme.csv",
                "text/csv",
                new byte[]{(byte) 0xFF, (byte) 0xFE, 0}
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("UTF-8");

        assertThatThrownBy(() -> service.stage(
                "module_materials",
                "uploads/guide.pdf",
                "application/octet-stream",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("文件内容");
    }

    @Test
    void opensFilesWithTheirCanonicalResponseType() throws Exception {
        Path file = temp.resolve("modules/module_materials/files/item/file.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.7", StandardCharsets.UTF_8);
        when(runtime.resolveWorkspacePath("modules/module_materials/files/item/file.pdf"))
                .thenReturn(file);

        ModuleDataService.ModuleDataAsset asset = service.open(
                "module_materials",
                "files/item/file.pdf"
        );

        assertThat(asset.contentType()).isEqualTo("application/pdf");
        assertThat(asset.size()).isEqualTo(8);
    }
}
