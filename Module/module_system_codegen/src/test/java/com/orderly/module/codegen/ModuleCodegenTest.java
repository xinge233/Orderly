package com.orderly.module.codegen;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleCodegenTest {

    private final ModuleCodegen module = new ModuleCodegen();

    @Test
    void exposesStableSystemModuleId() {
        assertThat(module.moduleId()).isEqualTo("module_system_codegen");
    }

    @Test
    void rejectsUndeclaredActionsUntilCodegenHostServicesExist() {
        assertThatThrownBy(() -> module.prepareSnapshot(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未声明");
        assertThatThrownBy(() -> module.execute(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未声明");
        assertThatThrownBy(() -> module.rollback(
                null,
                Map.of(),
                Map.of(),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未声明");
    }
}
