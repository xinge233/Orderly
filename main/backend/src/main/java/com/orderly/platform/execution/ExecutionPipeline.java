package com.orderly.platform.execution;

import com.orderly.platform.module.ModuleActionDefinition;
import com.orderly.platform.module.ModuleCommand;
import com.orderly.platform.module.ModuleCommandResult;
import com.orderly.platform.module.ModuleHandler;
import com.orderly.platform.module.ModuleRegistry;
import com.orderly.platform.module.ParameterSchema;
import com.orderly.platform.module.PlatformModule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 唯一受控的模块执行入口。
 *
 * <p>执行顺序固定为：动作与参数校验、计划确认、工作区授权、快照准备、
 * 模块执行、快照审计、失败回滚。任何较早阶段被拒绝的请求都不会进入
 * {@link ExecutionStage#EXECUTE}，模块也拿不到执行许可。</p>
 *
 * <p>当前没有 HTTP 执行端点，测试通过内存服务验证安全边界。接入真实业务时
 * 只能在此管线之上增加受控入口，不能从模块控制器直接调用模块。</p>
 */
@Service
public class ExecutionPipeline {

    private final ModuleRegistry registry;
    private final PlanConfirmationGuard confirmationGuard;
    private final WorkspaceBoundaryGuard workspaceBoundaryGuard;
    private final SnapshotAuditService snapshotAuditService;
    private final RollbackService rollbackService;
    private final List<ExecutionTrace> traces;

    /**
     * 注入中央阶段依赖。
     *
     * @param registry 模块与动作注册表
     * @param confirmationGuard 服务端确认策略
     * @param workspaceBoundaryGuard 工作区授权策略
     * @param snapshotAuditService 快照与审计服务
     * @param rollbackService 回滚服务
     * @param traces 只读阶段观察器
     */
    public ExecutionPipeline(
            ModuleRegistry registry,
            PlanConfirmationGuard confirmationGuard,
            WorkspaceBoundaryGuard workspaceBoundaryGuard,
            SnapshotAuditService snapshotAuditService,
            RollbackService rollbackService,
            List<ExecutionTrace> traces
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.confirmationGuard = Objects.requireNonNull(confirmationGuard);
        this.workspaceBoundaryGuard = Objects.requireNonNull(workspaceBoundaryGuard);
        this.snapshotAuditService = Objects.requireNonNull(snapshotAuditService);
        this.rollbackService = Objects.requireNonNull(rollbackService);
        this.traces = List.copyOf(Objects.requireNonNullElse(traces, List.of()));
    }

    /**
     * 执行一条受控请求。
     *
     * @param request 结构化请求
     * @return 带阶段追踪的结果
     */
    public ExecutionResult execute(ExecutionRequest request) {
        List<ExecutionStage> reached = new ArrayList<>();
        ModuleActionDefinition action = null;
        ExecutionSnapshot snapshot = ExecutionSnapshot.empty();
        boolean executionStarted = false;

        try {
            enter(ExecutionStage.VALIDATE_ACTION, request, reached);

            PlatformModule module = registry.find(request.moduleId()).orElse(null);
            if (module == null) {
                return reject(request, reached, ExecutionStage.VALIDATE_ACTION,
                        "目标模块未注册：" + request.moduleId(), null, snapshot);
            }
            if (!(module instanceof ModuleHandler handler)) {
                return reject(request, reached, ExecutionStage.VALIDATE_ACTION,
                        "目标模块未声明动作执行能力", null, snapshot);
            }

            action = registry.findAction(request.moduleId(), request.actionId()).orElse(null);
            if (action == null) {
                return reject(request, reached, ExecutionStage.VALIDATE_ACTION,
                        "目标模块不支持动作：" + request.actionId(), null, snapshot);
            }

            ParameterSchema schema = action.parameterSchema();
            List<String> parameterErrors = schema.validate(request.parameters());
            if (!parameterErrors.isEmpty()) {
                return reject(request, reached, ExecutionStage.VALIDATE_ACTION,
                        String.join("；", parameterErrors), action, snapshot);
            }

            enter(ExecutionStage.CONFIRM_PLAN, request, reached);
            if (!confirmationGuard.isConfirmed(request, action)) {
                return reject(request, reached, ExecutionStage.CONFIRM_PLAN,
                        "动作尚未获得服务端计划确认", action, snapshot);
            }

            enter(ExecutionStage.AUTHORIZE_WORKSPACE, request, reached);
            if (!workspaceBoundaryGuard.isAuthorized(request, action)) {
                return reject(request, reached, ExecutionStage.AUTHORIZE_WORKSPACE,
                        "工作区边界尚未授权该动作", action, snapshot);
            }

            enter(ExecutionStage.PREPARE_SNAPSHOT, request, reached);
            snapshot = snapshotAuditService.prepare(request, action);

            enter(ExecutionStage.EXECUTE, request, reached);
            executionStarted = true;
            ExecutionPermit permit = new ExecutionPermit(
                    request.requestId(),
                    request.moduleId(),
                    request.actionId()
            );
            ModuleCommandResult result = handler.execute(
                    new ModuleCommand(
                            request.moduleId(),
                            request.actionId(),
                            request.parameters()
                    ),
                    permit
            );

            if (result == null) {
                throw new IllegalStateException("模块返回了空结果");
            }

            enter(ExecutionStage.SNAPSHOT_AUDIT, request, reached);
            snapshotAuditService.record(new ExecutionAuditEvent(
                    request.requestId(),
                    request.planId(),
                    request.moduleId(),
                    request.actionId(),
                    ExecutionStage.SNAPSHOT_AUDIT,
                    result.status() == ModuleCommandResult.Status.COMPLETED
                            ? ExecutionResult.Outcome.COMPLETED
                            : ExecutionResult.Outcome.REJECTED,
                    result.message(),
                    snapshot
            ));

            if (result.status() != ModuleCommandResult.Status.COMPLETED) {
                enter(ExecutionStage.ROLLBACK_ON_FAILURE, request, reached);
                rollbackService.rollback(request, action, snapshot, result.message());
                return ExecutionResult.rejected(
                        request.requestId(),
                        ExecutionStage.EXECUTE,
                        result.message(),
                        reached
                );
            }

            return ExecutionResult.completed(
                    request.requestId(),
                    result.message(),
                    reached,
                    result.output()
            );
        } catch (RuntimeException exception) {
            ExecutionStage failedAt = lastStage(reached);
            if (executionStarted && action != null && failedAt != ExecutionStage.ROLLBACK_ON_FAILURE) {
                enter(ExecutionStage.ROLLBACK_ON_FAILURE, request, reached);
                rollbackService.rollback(
                        request,
                        action,
                        snapshot,
                        exception.getMessage()
                );
            }

            String message = Objects.requireNonNullElse(exception.getMessage(), "执行失败");
            recordFailure(request, failedAt, message, snapshot);
            return ExecutionResult.failed(request.requestId(), failedAt, message, reached);
        }
    }

    /**
     * 记录阶段并通知只读观察器。
     *
     * @param stage 当前阶段
     * @param request 执行请求
     * @param reached 已完成阶段列表
     */
    private void enter(
            ExecutionStage stage,
            ExecutionRequest request,
            List<ExecutionStage> reached
    ) {
        reached.add(stage);
        traces.forEach(trace -> trace.onStage(stage, request));
    }

    /**
     * 生成执行前拒绝结果并写入中央审计。
     *
     * @param request 执行请求
     * @param reached 已进入阶段
     * @param stage 拒绝阶段
     * @param message 拒绝原因
     * @param action 已知动作，可为空
     * @param snapshot 当前快照
     * @return 拒绝结果
     */
    private ExecutionResult reject(
            ExecutionRequest request,
            List<ExecutionStage> reached,
            ExecutionStage stage,
            String message,
            ModuleActionDefinition action,
            ExecutionSnapshot snapshot
    ) {
        snapshotAuditService.record(new ExecutionAuditEvent(
                request.requestId(),
                request.planId(),
                request.moduleId(),
                request.actionId(),
                stage,
                ExecutionResult.Outcome.REJECTED,
                message,
                snapshot
        ));
        return ExecutionResult.rejected(request.requestId(), stage, message, reached);
    }

    /**
     * 记录异常审计；审计服务自身异常不能再覆盖原始执行结果。
     */
    private void recordFailure(
            ExecutionRequest request,
            ExecutionStage stage,
            String message,
            ExecutionSnapshot snapshot
    ) {
        try {
            snapshotAuditService.record(new ExecutionAuditEvent(
                    request.requestId(),
                    request.planId(),
                    request.moduleId(),
                    request.actionId(),
                    stage,
                    ExecutionResult.Outcome.FAILED,
                    message,
                    snapshot
            ));
        } catch (RuntimeException ignored) {
            // 审计服务故障不能改变已经确定的失败结果；持久化实现需自行告警。
        }
    }

    /**
     * 返回最后进入的阶段。
     *
     * @param stages 阶段列表
     * @return 最后阶段；空列表视为校验阶段
     */
    private ExecutionStage lastStage(List<ExecutionStage> stages) {
        return stages.isEmpty()
                ? ExecutionStage.VALIDATE_ACTION
                : stages.getLast();
    }
}
