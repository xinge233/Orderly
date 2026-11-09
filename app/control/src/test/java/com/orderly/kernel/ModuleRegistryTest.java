package com.orderly.kernel;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRegistryTest {
    @Test
    void startsWithZeroModules() {
        Path contracts = Path.of("..", "contracts").toAbsolutePath().normalize();

        ModuleRegistry registry = new ModuleRegistry(contracts.toString());

        assertThat(registry.moduleCount()).isZero();
        assertThat(registry.modules()).isEmpty();
    }
}
