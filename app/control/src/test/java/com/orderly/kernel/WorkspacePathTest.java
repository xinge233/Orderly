package com.orderly.kernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePathTest {
    @Test
    void acceptsPathInsideWorkspace() {
        WorkspacePath path = WorkspacePath.checkedRelative("C:/workspace", "folder/item.txt");

        assertThat(path.relativePath()).isEqualTo("folder/item.txt");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        assertThatThrownBy(() -> WorkspacePath.checkedRelative("C:/workspace", "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
