package com.orderly.module.fs;

import com.orderly.kernel.AuthorizedFileRoot;
import com.orderly.kernel.FileEntry;
import com.orderly.kernel.FileReadResult;
import com.orderly.kernel.FileWriteResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.TrashEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileModuleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void grantedReadReturnsTextWithoutExposingFilePath() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("note.txt"), "第一行\n第二行");
        FakeRuntime runtime = new FakeRuntime(root);
        FileModule module = new FileModule();

        runtime.grant("token-1", "note.txt");

        Map<String, Object> payload = module.query(
                "fs.readGrantedText",
                Map.of("token", "token-1", "offset", "0", "maxChars", "8000"),
                runtime
        );

        assertThat(payload.get("content")).isEqualTo("第一行\n第二行");
        assertThat(payload).doesNotContainKeys("path", "rootId");
    }

    @Test
    void unknownTokenFailsWithoutGuessingAFile() {
        FakeRuntime runtime = new FakeRuntime(temporaryDirectory);
        FileModule module = new FileModule();

        assertThatThrownBy(() -> module.query(
                "fs.readGrantedText",
                Map.of("token", UUID.randomUUID().toString()),
                runtime
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("读取令牌不存在");
    }

    private static final class FakeRuntime implements ModuleRuntime {
        private final Path root;
        private final Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
        private final Map<String, String> grants = new LinkedHashMap<>();

        private FakeRuntime(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public String workspaceLabel() {
            return "测试工作区";
        }

        @Override
        public List<AuthorizedFileRoot> listFileRoots() {
            return List.of(new AuthorizedFileRoot("root-1", "测试根", true, true, true));
        }

        @Override
        public Path resolveWorkspacePath(String relativePath) {
            return root.resolve(relativePath).normalize();
        }

        @Override
        public Path resolveAuthorizedPath(String rootId, String relativePath) {
            if (!"root-1".equals(rootId)) {
                throw new IllegalArgumentException("文件根不存在或未授权");
            }
            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("路径越过授权根边界");
            }
            return resolved;
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public List<FileEntry> listDirectory(Path directory) {
            return listEntries(directory, root);
        }

        @Override
        public List<FileEntry> listAuthorizedDirectory(Path directory) {
            return listEntries(directory, root);
        }

        private List<FileEntry> listEntries(Path directory, Path base) {
            try (var stream = Files.list(directory)) {
                return stream.map(path -> {
                    try {
                        return new FileEntry(
                                path.getFileName().toString(),
                                base.relativize(path).toString().replace('\\', '/'),
                                Files.isDirectory(path),
                                Files.size(path),
                                Files.getLastModifiedTime(path).toInstant().toString()
                        );
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }).toList();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Path createDirectory(Path directory) {
            try {
                return Files.createDirectories(directory);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Path createTextFile(Path file, String content) {
            try {
                Files.createDirectories(file.getParent());
                return Files.writeString(file, content);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Path createAuthorizedTextFile(Path file, String content) {
            return createTextFile(file, content);
        }

        @Override
        public String readTextFile(Path file) {
            try {
                return Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public FileReadResult readGrantedText(String token, int offset, int maxChars) {
            String relative = grants.get(token);
            if (relative == null) {
                throw new IllegalArgumentException("读取令牌不存在或已过期");
            }
            Path file = resolveAuthorizedPath("root-1", relative);
            String content = readTextFile(file);
            int start = Math.max(0, Math.min(offset, content.length()));
            int end = Math.min(content.length(), start + maxChars);
            return new FileReadResult(
                    content.substring(start, end),
                    start,
                    end,
                    content.getBytes(StandardCharsets.UTF_8).length,
                    end >= content.length(),
                    "UTF-8"
            );
        }

        private void grant(String token, String relativePath) {
            grants.put(token, relativePath);
        }

        @Override
        public void openFile(Path file) {
        }

        @Override
        public void move(Path source, Path target) {
            try {
                Files.move(source, target);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public TrashEntry moveToTrash(Path source, String planId, String stepId) {
            throw new UnsupportedOperationException("test fake does not implement trash");
        }

        @Override
        public TrashEntry moveToTrashForRecovery(Path source, String planId, String stepId) {
            throw new UnsupportedOperationException("test fake does not implement trash");
        }

        @Override
        public Path restoreFromTrash(String trashId) {
            throw new UnsupportedOperationException("test fake does not implement trash");
        }

        @Override
        public List<TrashEntry> listTrash() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> readItems(String collection) {
            return collections.getOrDefault(collection, List.of()).stream()
                    .map(LinkedHashMap::new)
                    .map(Map::copyOf)
                    .toList();
        }

        @Override
        public boolean hasItems(String collection) {
            return collections.containsKey(collection);
        }

        @Override
        public void writeItems(String collection, List<Map<String, Object>> items) {
            List<Map<String, Object>> copy = items.stream()
                    .map(item -> (Map<String, Object>) new LinkedHashMap<String, Object>(item))
                    .toList();
            collections.put(collection, copy);
        }

        @Override
        public FileWriteResult writeAuthorizedTextFile(
                Path file,
                String content,
                boolean overwrite,
                String planId,
                String stepId
        ) {
            throw new UnsupportedOperationException("test fake does not implement write");
        }

        @Override
        public FileWriteResult writeAuthorizedBinaryFile(
                Path stagedFile,
                Path target,
                boolean overwrite,
                String planId,
                String stepId
        ) {
            throw new UnsupportedOperationException("test fake does not implement write");
        }

        @Override
        public String newId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public String relativePath(Path path) {
            return root.relativize(path).toString().replace('\\', '/');
        }
    }
}
