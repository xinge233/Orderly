package com.orderly.host.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.FileWriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceRuntimeAuthorizedFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void firstStartDoesNotAuthorizeAnyFileRootWithoutUserAction() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        WorkspaceRuntime runtime = runtime(data);

        assertThat(runtime.listFileRoots()).isEmpty();
        assertThat(new ObjectMapper().readTree(
                Files.readString(data.resolve("file-roots.json"))
        ).isEmpty()).isTrue();
    }

    @Test
    void startupRemovesLegacyImplicitUserHomeRootAndPersistsExplicitRoots() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path explicitRoot = Files.createDirectories(temporaryDirectory.resolve("explicit"));
        Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        List<Map<String, Object>> roots = List.of(
                Map.of(
                        "id", "legacy-user-home",
                        "label", "用户目录",
                        "path", userHome.toString(),
                        "read", true,
                        "write", true,
                        "delete", true
                ),
                Map.of(
                        "id", "explicit-root",
                        "label", "显式资料",
                        "path", explicitRoot.toString(),
                        "read", true,
                        "write", false,
                        "delete", false
                )
        );
        Files.createDirectories(data);
        ObjectMapper objectMapper = new ObjectMapper();
        Files.writeString(data.resolve("file-roots.json"), objectMapper.writeValueAsString(roots));

        WorkspaceRuntime runtime = runtime(data);

        assertThat(runtime.listFileRoots())
                .extracting(root -> root.id())
                .containsExactly("explicit-root");
        var persisted = objectMapper.readTree(Files.readString(data.resolve("file-roots.json")));
        assertThat(persisted.size()).isEqualTo(1);
        assertThat(persisted.get(0).path("id").asText()).isEqualTo("explicit-root");
        assertThat(persisted.get(0).path("label").asText()).isEqualTo("显式资料");
    }

    @Test
    void authorizedRootDescriptorDoesNotExposeAbsolutePath() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        WorkspaceRuntime runtime = runtime(data);

        var descriptor = runtime.authorizeFileRoot(root.toString(), "测试根", true, true);

        assertThat(descriptor.label()).isEqualTo("测试根");
        assertThat(descriptor.read()).isTrue();
        assertThat(descriptor.write()).isTrue();
        assertThat(descriptor.delete()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(runtime.listFileRoots()))
                .doesNotContain(root.toString());
        Files.writeString(root.resolve("note.txt"), "撤销后不可读取");
        String token = runtime.issueReadGrant(descriptor.id(), "note.txt", "summary")
                .get("token")
                .toString();
        runtime.revokeFileRoot(descriptor.id());
        assertThat(runtime.listFileRoots()).isEmpty();
        assertThatThrownBy(() -> runtime.readGrantedText(token, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已过期");
    }

    @Test
    void overwriteUsesTrashBackupAndCanRestoreOriginalContent() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        Path file = root.resolve("note.txt");
        Files.writeString(file, "old");
        WorkspaceRuntime runtime = runtime(data);
        var authorized = runtime.authorizeFileRoot(root.toString(), "测试根", true, true);
        Path target = runtime.resolveAuthorizedPath(authorized.id(), "note.txt");

        FileWriteResult result = runtime.writeAuthorizedTextFile(
                target,
                "new",
                true,
                "write-test",
                "step-1"
        );

        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(result.backup()).isNotNull();
        assertThat(runtime.listTrash()).extracting(entry -> entry.id())
                .contains(result.backup().id());

        runtime.moveToTrash(target, "rollback-test", "step-1");
        runtime.restoreFromTrash(result.backup().id());
        assertThat(Files.readString(target)).isEqualTo("old");
    }

    @Test
    void overwriteUsesRecoveryBackupWithoutGrantingDirectDelete() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        Files.writeString(root.resolve("note.txt"), "old");
        WorkspaceRuntime runtime = runtime(data);
        var authorized = runtime.authorizeFileRoot(root.toString(), "测试根", true, false);
        Path target = runtime.resolveAuthorizedPath(authorized.id(), "note.txt");

        FileWriteResult result = runtime.writeAuthorizedTextFile(
                target,
                "new",
                true,
                "write-test",
                "step-1"
        );
        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(result.backup()).isNotNull();
        assertThatThrownBy(() -> runtime.moveToTrash(target, "delete-test", "step-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("删除能力");
    }

    @Test
    void authorizedRelativePathCannotEscapeRoot() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        WorkspaceRuntime runtime = runtime(data);
        var authorized = runtime.authorizeFileRoot(root.toString(), "测试根", true, true);

        assertThatThrownBy(() -> runtime.resolveAuthorizedPath(authorized.id(), "../escape.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权根边界");
    }

    @Test
    void trashedAuthorizedFileReturnsNoHostAbsolutePathAndCanBeRestored() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        Path file = root.resolve("note.txt");
        Files.writeString(file, "待回收");
        WorkspaceRuntime runtime = runtime(data);
        var authorized = runtime.authorizeFileRoot(root.toString(), "测试根", true, true);

        var entry = runtime.moveToTrash(
                runtime.resolveAuthorizedPath(authorized.id(), "note.txt"),
                "delete-test",
                "step-1"
        );

        assertThat(entry.originalPath()).startsWith(authorized.id() + ":");
        assertThat(new ObjectMapper().writeValueAsString(entry.asMap()))
                .doesNotContain(root.toString(), data.toString());
        Path restored = runtime.restoreFromTrash(entry.id());
        assertThat(Files.readString(restored)).isEqualTo("待回收");
    }

    @Test
    void readGrantUsesOpaqueTokenAndExpiresOnlyInMemory() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Path root = Files.createDirectories(temporaryDirectory.resolve("authorized"));
        Files.writeString(root.resolve("note.txt"), "授权正文");
        WorkspaceRuntime runtime = runtime(data);
        var authorized = runtime.authorizeFileRoot(root.toString(), "测试根", true, true);

        var grant = runtime.issueReadGrant(authorized.id(), "note.txt", "summary");
        var result = runtime.readGrantedText(grant.get("token").toString(), 0, 100);

        assertThat(result.content()).isEqualTo("授权正文");
        assertThatThrownBy(() -> runtime.readGrantedText("missing-token", 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已过期");
    }

    private WorkspaceRuntime runtime(Path data) {
        WorkspaceRuntime runtime = new WorkspaceRuntime(data.toString(), new ObjectMapper());
        runtime.initialize();
        return runtime;
    }
}
