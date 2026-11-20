package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleControlCoordinatorTest {

    @Test
    void shutdownRequiresExecutionsToFinishAndFreezesNewWork() {
        ModuleControlCoordinator coordinator = new ModuleControlCoordinator();
        ModuleControlCoordinator.ExecutionPermit permit = coordinator.beginExecution();

        assertThatThrownBy(coordinator::beginShutdown)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("HOST_EXECUTION_IN_PROGRESS");

        permit.close();
        assertThat(coordinator.beginShutdown()).isTrue();
        assertThat(coordinator.beginShutdown()).isFalse();
        assertThatThrownBy(coordinator::beginExecution)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("HOST_SHUTTING_DOWN");
        assertThatThrownBy(() -> coordinator.mutate(() -> "changed"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("HOST_SHUTTING_DOWN");

        coordinator.cancelShutdown();
        assertThatCode(() -> coordinator.beginExecution().close())
                .doesNotThrowAnyException();
    }
}
