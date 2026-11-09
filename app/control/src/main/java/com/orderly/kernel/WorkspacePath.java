package com.orderly.kernel;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Lexical workspace-path guard. S1 does not access the filesystem.
 */
public final class WorkspacePath {
    private final String relativePath;

    private WorkspacePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public static WorkspacePath checkedRelative(String workspaceRoot, String requestedPath) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(requestedPath, "requestedPath");
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Path requested = Path.of(requestedPath);
        Path candidate = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace");
        }
        return new WorkspacePath(root.relativize(candidate).toString().replace('\\', '/'));
    }

    public String relativePath() {
        return relativePath;
    }
}
