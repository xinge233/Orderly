package com.orderly.platform.execution;

import com.orderly.platform.module.ActionParameterDefinition;
import com.orderly.platform.module.ActionParameterType;
import com.orderly.platform.module.ActionRiskLevel;
import com.orderly.platform.module.ModuleActionDefinition;
import com.orderly.platform.module.ModuleCommand;
import com.orderly.platform.module.ModuleCommandResult;
import com.orderly.platform.module.ModuleDescriptor;
import com.orderly.platform.module.ModuleHandler;
import com.orderly.platform.module.ModuleRegistry;
import com.orderly.platform.module.ParameterSchema;
import com.orderly.platform.module.PlatformModule;
import com.orderly.platform.module.SampleTestModule;
import com.orderly.platform.module.WorkspaceAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证中央执行管线的安全顺序与执行阻断。
 */
class ExecutionPipelineTest {

    @Test
    void shouldRunAllPreExecutionStagesBeforeTheSampleAction() {
        List<ExecutionStage> stages = new ArrayList<>();
        ExecutionPipeline pipeline = pipeline(
                new SampleTestModule(),
                new SkeletonPlanConfirmationGuard(),
                new SkeletonWorkspaceBoundaryGuard(),
                stages,
                new RecordingRollback()
        );

        ExecutionResult result = pipeline.execute(request(
                "request-1",
                "sample-empty",
                "ping",
                Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.COMPLETED);
        assertThat(stages).containsExactly(
                ExecutionStage.VALIDATE_ACTION,
                ExecutionStage.CONFIRM_PLAN,
                ExecutionStage.AUTHORIZE_WORKSPACE,
                ExecutionStage.PREPARE_SNAPSHOT,
                ExecutionStage.EXECUTE,
                ExecutionStage.SNAPSHOT_AUDIT
        );
    }

    @Test
    void shouldRejectInvalidParametersBeforeExecution() {
        RecordingModule module = new RecordingModule(
                new ModuleActionDefinition(
                        "echo",
                        "回声测试",
                        "验证参数校验",
                        new ParameterSchema(List.of(
                                new ActionParameterDefinition(
                                        "message",
                                        "消息",
                                        ActionParameterType.STRING,
                                        true
                                )
                        )),
                        ActionRiskLevel.READ_ONLY,
                        false,
                        true,
                        false,
                        WorkspaceAccess.NONE
                )
        );
        List<ExecutionStage> stages = new ArrayList<>();
        ExecutionPipeline pipeline = pipeline(
                module,
                new SkeletonPlanConfirmationGuard(),
                new SkeletonWorkspaceBoundaryGuard(),
                stages,
                new RecordingRollback()
        );

        ExecutionResult result = pipeline.execute(
                request("request-2", module.descriptor().id(), "echo", Map.of())
        );

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.failedAt()).isEqualTo(ExecutionStage.VALIDATE_ACTION);
        assertThat(result.message()).contains("缺少必填参数");
        assertThat(module.executionCount).isZero();
        assertThat(stages).containsExactly(ExecutionStage.VALIDATE_ACTION);
    }

    @Test
    void shouldStopAtConfirmationAndNeverExecute() {
        RecordingModule module = new RecordingModule(writeAction());
        List<ExecutionStage> stages = new ArrayList<>();
        ExecutionPipeline pipeline = pipeline(
                module,
                new SkeletonPlanConfirmationGuard(),
                new SkeletonWorkspaceBoundaryGuard(),
                stages,
                new RecordingRollback()
        );

        ExecutionResult result = pipeline.execute(request(
                "request-3",
                module.descriptor().id(),
                "move",
                Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.failedAt()).isEqualTo(ExecutionStage.CONFIRM_PLAN);
        assertThat(module.executionCount).isZero();
        assertThat(stages).containsExactly(
                ExecutionStage.VALIDATE_ACTION,
                ExecutionStage.CONFIRM_PLAN
        );
    }

    @Test
    void shouldStopAtWorkspaceBoundaryWhenAuthorizationIsUnavailable() {
        RecordingModule module = new RecordingModule(writeAction());
        List<ExecutionStage> stages = new ArrayList<>();
        ExecutionPipeline pipeline = pipeline(
                module,
                (request, action) -> true,
                new SkeletonWorkspaceBoundaryGuard(),
                stages,
                new RecordingRollback()
        );

        ExecutionResult result = pipeline.execute(request(
                "request-4",
                module.descriptor().id(),
                "move",
                Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.failedAt()).isEqualTo(ExecutionStage.AUTHORIZE_WORKSPACE);
        assertThat(module.executionCount).isZero();
        assertThat(stages).containsExactly(
                ExecutionStage.VALIDATE_ACTION,
                ExecutionStage.CONFIRM_PLAN,
                ExecutionStage.AUTHORIZE_WORKSPACE
        );
    }

    @Test
    void shouldEnterRollbackStageWhenModuleExecutionFails() {
        RecordingModule module = new RecordingModule(
                new ModuleActionDefinition(
                        "explode",
                        "异常测试",
                        "只用于管线失败测试",
                        ParameterSchema.empty(),
                        ActionRiskLevel.READ_ONLY,
                        false,
                        true,
                        false,
                        WorkspaceAccess.NONE
                )
        );
        module.throwOnExecute = true;
        List<ExecutionStage> stages = new ArrayList<>();
        RecordingRollback rollback = new RecordingRollback();
        ExecutionPipeline pipeline = pipeline(
                module,
                new SkeletonPlanConfirmationGuard(),
                new SkeletonWorkspaceBoundaryGuard(),
                stages,
                rollback
        );

        ExecutionResult result = pipeline.execute(request(
                "request-5",
                module.descriptor().id(),
                "explode",
                Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.FAILED);
        assertThat(stages).containsExactly(
                ExecutionStage.VALIDATE_ACTION,
                ExecutionStage.CONFIRM_PLAN,
                ExecutionStage.AUTHORIZE_WORKSPACE,
                ExecutionStage.PREPARE_SNAPSHOT,
                ExecutionStage.EXECUTE,
                ExecutionStage.ROLLBACK_ON_FAILURE
        );
        assertThat(rollback.calls).isEqualTo(1);
    }

    @Test
    void shouldRejectUnknownActionBeforeExecution() {
        RecordingModule module = new RecordingModule(writeAction());
        List<ExecutionStage> stages = new ArrayList<>();
        ExecutionPipeline pipeline = pipeline(
                module,
                (request, action) -> true,
                (request, action) -> true,
                stages,
                new RecordingRollback()
        );

        ExecutionResult result = pipeline.execute(request(
                "request-6",
                module.descriptor().id(),
                "unknown",
                Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(ExecutionResult.Outcome.REJECTED);
        assertThat(result.failedAt()).isEqualTo(ExecutionStage.VALIDATE_ACTION);
        assertThat(module.executionCount).isZero();
        assertThat(stages).containsExactly(ExecutionStage.VALIDATE_ACTION);
    }

    /**
     * 创建带记录型快照审计的执行管线。
     */
    private ExecutionPipeline pipeline(
            PlatformModule module,
            PlanConfirmationGuard confirmationGuard,
            WorkspaceBoundaryGuard workspaceGuard,
            List<ExecutionStage> stages,
            RollbackService rollbackService
    ) {
        ModuleRegistry registry = new ModuleRegistry(List.of(module));
        SnapshotAuditService audit = new SnapshotAuditService() {
            @Override
            public ExecutionSnapshot prepare(
                    ExecutionRequest request,
                    ModuleActionDefinition action
            ) {
                return ExecutionSnapshot.empty();
            }

            @Override
            public void record(ExecutionAuditEvent event) {
                // 阶段顺序由 ExecutionTrace 单独验证。
            }
        };
        ExecutionTrace trace = (stage, request) -> stages.add(stage);
        return new ExecutionPipeline(
                registry,
                confirmationGuard,
                workspaceGuard,
                audit,
                rollbackService,
                List.of(trace)
        );
    }

    /**
     * 创建固定测试请求。
     */
    private ExecutionRequest request(
            String requestId,
            String moduleId,
            String actionId,
            Map<String, Object> parameters
    ) {
        return new ExecutionRequest(
                requestId,
                "plan-" + requestId,
                moduleId,
                actionId,
                parameters,
                ""
        );
    }

    /**
     * 创建需要确认和工作区写入的测试动作。
     */
    private ModuleActionDefinition writeAction() {
        return new ModuleActionDefinition(
                "move",
                "移动测试",
                "不执行真实文件操作",
                ParameterSchema.empty(),
                ActionRiskLevel.WRITE,
                true,
                true,
                true,
                WorkspaceAccess.WRITE
        );
    }

    /**
     * 不产生真实副作用的可观测测试模块。
     */
    private static final class RecordingModule implements PlatformModule, ModuleHandler {
        private final ModuleActionDefinition action;
        private int executionCount;
        private boolean throwOnExecute;

        private RecordingModule(ModuleActionDefinition action) {
            this.action = action;
        }

        @Override
        public ModuleDescriptor descriptor() {
            return new ModuleDescriptor(
                    "test-module",
                    "测试模块",
                    "仅用于中央管线测试",
                    1
            );
        }

        @Override
        public List<ModuleActionDefinition> actions() {
            return List.of(action);
        }

        @Override
        public ModuleCommandResult execute(ModuleCommand command, ExecutionPermit permit) {
            permit.requireFor(descriptor().id(), action.id());
            executionCount++;
            if (throwOnExecute) {
                throw new IllegalStateException("模拟执行失败");
            }
            return ModuleCommandResult.completed("测试动作完成");
        }
    }

    /**
     * 记录回滚调用次数的测试实现。
     */
    private static final class RecordingRollback implements RollbackService {
        private int calls;

        @Override
        public RollbackResult rollback(
                ExecutionRequest request,
                ModuleActionDefinition action,
                ExecutionSnapshot snapshot,
                String reason
        ) {
            calls++;
            return RollbackResult.notRequired("测试无需真实回滚");
        }
    }
}
