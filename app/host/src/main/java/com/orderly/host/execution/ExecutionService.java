package com.orderly.host.execution;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.plan.ExecutionPlan;
import com.orderly.host.plan.ExecutionStep;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanStatus;
import com.orderly.host.plan.PlanView;
import com.orderly.host.plan.StepStatus;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中央执行与回滚服务。
 *
 * <p>模块永远只收到已校验、已确认的 {@link ModuleInvocation}。快照时机、
 * 逆序回滚、风险确认和事件记录都由本服务统一担保。</p>
 */
@Service
public class ExecutionService {

    private final PlanService plans;
    private final ContractCatalog catalog;
    private final WorkspaceRuntime runtime;
    private final ExecutionJournal journal;
    private final Object executionLock = new Object();

    public ExecutionService(
            PlanService plans,
            ContractCatalog catalog,
            WorkspaceRuntime runtime,
            ExecutionJournal journal
    ) {
        this.plans = plans;
        this.catalog = catalog;
        this.runtime = runtime;
        this.journal = journal;
    }

    public ExecutionRunResult confirm(String planId, boolean confirmed, String confirmation) {
        ExecutionPlan plan = plans.require(planId);
        synchronized (executionLock) {
            if (plan.status() != PlanStatus.PREVIEW) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PLAN_STATE_CONFLICT",
                        "计划当前状态为 " + plan.status() + "，不能再次确认"
                );
            }
            if (plan.steps().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "PLAN_NEEDS_CLARIFICATION",
                        "澄清问题不能执行；请重新描述目标");
            }
            validateConfirmation(plan, confirmed, confirmation);

            plan.status(PlanStatus.RUNNING);
            journal.append("PLAN", plan.id(), null, null, null, "RUNNING",
                    "计划已确认，开始执行");

            List<ExecutionStep> completed = new ArrayList<>();
            try {
                for (ExecutionStep step : plan.steps()) {
                    executeStep(plan, step);
                    completed.add(step);
                }
                plan.status(PlanStatus.COMPLETED);
                journal.append("PLAN", plan.id(), null, null, null, "COMPLETED",
                        "计划执行完成");
                return new ExecutionRunResult(
                        "COMPLETED", "COMPLETED", "计划执行完成", PlanView.from(plan));
            } catch (RuntimeException exception) {
                String message = safeMessage(exception);
                plan.status(PlanStatus.REJECTED);
                journal.append("PLAN", plan.id(), null, null, null, "FAILED", message);
                boolean rolledBack = rollbackSteps(plan, completed, "失败后自动回滚");
                if (!rolledBack) {
                    plan.status(PlanStatus.PARTIALLY_ROLLED_BACK);
                }
                return new ExecutionRunResult(
                        rolledBack ? "FAILED_ROLLED_BACK" : "FAILED_PARTIALLY_ROLLED_BACK",
                        rolledBack ? "FAILED_ROLLED_BACK" : "FAILED_PARTIALLY_ROLLED_BACK",
                        message,
                        PlanView.from(plan)
                );
            }
        }
    }

    public ExecutionRunResult rollback(String planId) {
        ExecutionPlan plan = plans.require(planId);
        synchronized (executionLock) {
            if (plan.status() != PlanStatus.COMPLETED && plan.status() != PlanStatus.PARTIALLY_ROLLED_BACK) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PLAN_NOT_ROLLBACKABLE",
                        "只有已完成或部分回滚失败的整单可以继续回滚"
                );
            }
            List<ExecutionStep> completed = plan.steps().stream()
                    .filter(step -> step.status() == StepStatus.COMPLETED)
                    .toList();
            boolean success = rollbackSteps(plan, completed, "用户请求整单回滚");
            plan.status(success ? PlanStatus.ROLLED_BACK : PlanStatus.PARTIALLY_ROLLED_BACK);
            journal.append("PLAN", plan.id(), null, null, null, plan.status().name(),
                    success ? "整单已回滚" : "整单回滚未完全成功，请检查事件记录");
            return new ExecutionRunResult(
                    success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                    success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                    success ? "整单已回滚" : "部分步骤回滚失败",
                    PlanView.from(plan)
            );
        }
    }

    private void validateConfirmation(ExecutionPlan plan, boolean confirmed, String phrase) {
        boolean hasWrite = plan.steps().stream()
                .anyMatch(step -> step.risk() != RiskLevel.READ_ONLY);
        if (hasWrite && !confirmed) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PLAN_CONFIRMATION_REQUIRED",
                    "计划包含写入或删除步骤，必须确认后才能执行"
            );
        }
        for (ExecutionStep step : plan.steps()) {
            if (step.risk() == RiskLevel.DELETE
                    && !Objects.requireNonNullElse(phrase, "").trim().equals(step.confirmationPhrase())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "DELETE_CONFIRMATION_REQUIRED",
                        "删除步骤必须输入确认词：" + step.confirmationPhrase()
                );
            }
        }
    }

    private void executeStep(ExecutionPlan plan, ExecutionStep step) {
        ActionContract action = catalog.requireAction(step.actionId());
        OrderlyModule module = catalog.requireExecutor(step.moduleId());
        ModuleInvocation invocation = new ModuleInvocation(
                step.moduleId(),
                step.actionId(),
                plan.id(),
                step.id(),
                step.arguments()
        );

        step.status(StepStatus.RUNNING);
        journal.append("STEP", plan.id(), step.id(), step.moduleId(), step.actionId(),
                "RUNNING", step.title());
        try {
            Map<String, Object> snapshot = module.prepareSnapshot(invocation, runtime);
            if (snapshot == null) {
                throw new IllegalStateException("模块未提供快照");
            }
            step.snapshot(snapshot);
            ModuleResult result = module.execute(invocation, runtime);
            if (result == null) {
                throw new IllegalStateException("模块返回空结果");
            }
            step.output(result.output());
            step.message(result.message());
            step.status(StepStatus.COMPLETED);
            journal.append("STEP", plan.id(), step.id(), step.moduleId(), step.actionId(),
                    "COMPLETED", result.message());
        } catch (RuntimeException exception) {
            step.message(safeMessage(exception));
            step.status(StepStatus.FAILED);
            journal.append("STEP", plan.id(), step.id(), step.moduleId(), action.id(),
                    "FAILED", step.message());
            throw exception;
        }
    }

    private boolean rollbackSteps(ExecutionPlan plan, List<ExecutionStep> steps, String reason) {
        boolean allSucceeded = true;
        journal.append("ROLLBACK", plan.id(), null, null, null, "RUNNING", reason);
        for (int index = steps.size() - 1; index >= 0; index--) {
            ExecutionStep step = steps.get(index);
            if (step.status() != StepStatus.COMPLETED) {
                continue;
            }
            OrderlyModule module = catalog.requireExecutor(step.moduleId());
            ModuleInvocation invocation = new ModuleInvocation(
                    step.moduleId(),
                    step.actionId(),
                    plan.id(),
                    step.id(),
                    step.arguments()
            );
            try {
                ModuleResult rollback = module.rollback(
                        invocation,
                        step.snapshot(),
                        step.output(),
                        runtime
                );
                step.status(StepStatus.ROLLED_BACK);
                journal.append("ROLLBACK_STEP", plan.id(), step.id(), step.moduleId(), step.actionId(),
                        "ROLLED_BACK", rollback.message());
            } catch (RuntimeException exception) {
                allSucceeded = false;
                journal.append("ROLLBACK_STEP", plan.id(), step.id(), step.moduleId(), step.actionId(),
                        "FAILED", safeMessage(exception));
            }
        }
        journal.append("ROLLBACK", plan.id(), null, null, null,
                allSucceeded ? "COMPLETED" : "FAILED",
                allSucceeded ? "回滚完成" : "回滚部分失败，已保留审计记录");
        return allSucceeded;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }
}
