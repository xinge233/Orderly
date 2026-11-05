package com.orderly.platform.module;

import com.orderly.platform.execution.ExecutionPipeline;
import com.orderly.platform.execution.ExecutionRequest;
import com.orderly.platform.execution.ExecutionResult;
import com.orderly.platform.execution.NoopSnapshotAuditService;
import com.orderly.platform.execution.SkeletonPlanConfirmationGuard;
import com.orderly.platform.execution.SkeletonRollbackService;
import com.orderly.platform.execution.SkeletonWorkspaceBoundaryGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证空模块只接受显式声明的无副作用动作。
 */
class ModuleDispatchServiceTest {

    private final ModuleRegistry registry = new ModuleRegistry(List.of(new SampleTestModule()));
    private final ModuleDispatchService service = new ModuleDispatchService(
            new ExecutionPipeline(
                    registry,
                    new SkeletonPlanConfirmationGuard(),
                    new SkeletonWorkspaceBoundaryGuard(),
                    new NoopSnapshotAuditService(),
                    new SkeletonRollbackService(),
                    List.of()
            )
    );

    @Test
    void shouldDispatchDeclaredPingAction() {
        ExecutionResult result = service.dispatch(
                new ExecutionRequest(
                        "request-1",
                        "plan-1",
                        "sample-empty",
                        "ping",
                        Map.of(),
                        ""
                )
        );

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.COMPLETED);
        assertThat(result.output()).containsEntry("sideEffects", false);
    }

    @Test
    void shouldRejectUnknownActionBeforeHandling() {
        ExecutionResult result = service.dispatch(
                new ExecutionRequest(
                        "request-2",
                        "plan-1",
                        "sample-empty",
                        "delete-all",
                        Map.of(),
                        ""
                )
        );

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.message()).contains("不支持动作");
        assertThat(result.completedStages()).doesNotContain(
                com.orderly.platform.execution.ExecutionStage.EXECUTE
        );
    }

    @Test
    void shouldRejectUnknownModule() {
        ExecutionResult result = service.dispatch(
                new ExecutionRequest(
                        "request-3",
                        "plan-1",
                        "missing",
                        "ping",
                        Map.of(),
                        ""
                )
        );

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.message()).contains("未注册");
    }
}
