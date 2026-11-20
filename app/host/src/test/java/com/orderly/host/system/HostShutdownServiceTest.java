package com.orderly.host.system;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostShutdownServiceTest {

    @Test
    void acceptedShutdownIsAuditedScheduledAndIdempotent() {
        ApplicationContext context = mock(ApplicationContext.class);
        ExecutionJournal journal = mock(ExecutionJournal.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ModuleControlCoordinator coordinator = new ModuleControlCoordinator();
        HostShutdownService service = new HostShutdownService(
                context,
                coordinator,
                journal,
                scheduler,
                true
        );

        HostShutdownService.ShutdownAccepted first = service.requestShutdown();
        HostShutdownService.ShutdownAccepted second = service.requestShutdown();

        assertThat(first.status()).isEqualTo("STOPPING");
        assertThat(first.message()).contains("正在停止");
        assertThat(second.message()).contains("已在退出流程中");
        verify(journal).append(
                eq("HOST_LIFECYCLE"),
                eq("SYSTEM"),
                eq(null),
                eq(null),
                eq(null),
                eq("shutdown"),
                eq("STOPPING"),
                anyString()
        );
        verify(scheduler).schedule(
                any(Runnable.class),
                eq(HostShutdownService.EXIT_DELAY_MILLIS),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    void activeExecutionAndDisabledConfigurationRejectShutdown() {
        ApplicationContext context = mock(ApplicationContext.class);
        ExecutionJournal journal = mock(ExecutionJournal.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ModuleControlCoordinator coordinator = new ModuleControlCoordinator();
        HostShutdownService service = new HostShutdownService(
                context,
                coordinator,
                journal,
                scheduler,
                true
        );

        try (ModuleControlCoordinator.ExecutionPermit ignored = coordinator.beginExecution()) {
            assertThatThrownBy(service::requestShutdown)
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).code())
                    .isEqualTo("HOST_EXECUTION_IN_PROGRESS");
        }
        verify(scheduler, never()).schedule(
                any(Runnable.class),
                eq(HostShutdownService.EXIT_DELAY_MILLIS),
                eq(TimeUnit.MILLISECONDS)
        );

        HostShutdownService disabled = new HostShutdownService(
                context,
                new ModuleControlCoordinator(),
                journal,
                scheduler,
                false
        );
        assertThatThrownBy(disabled::requestShutdown)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("HOST_SHUTDOWN_DISABLED");
    }

    @Test
    void schedulingFailureRestoresExecutionAvailability() {
        ApplicationContext context = mock(ApplicationContext.class);
        ExecutionJournal journal = mock(ExecutionJournal.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ModuleControlCoordinator coordinator = new ModuleControlCoordinator();
        when(scheduler.schedule(
                any(Runnable.class),
                eq(HostShutdownService.EXIT_DELAY_MILLIS),
                eq(TimeUnit.MILLISECONDS)
        )).thenThrow(new IllegalStateException("scheduler unavailable"));
        HostShutdownService service = new HostShutdownService(
                context,
                coordinator,
                journal,
                scheduler,
                true
        );

        assertThatThrownBy(service::requestShutdown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduler unavailable");
        try (ModuleControlCoordinator.ExecutionPermit ignored = coordinator.beginExecution()) {
            assertThat(ignored).isNotNull();
        }
    }
}
