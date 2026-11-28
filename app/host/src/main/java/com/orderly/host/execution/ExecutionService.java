package com.orderly.host.execution;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import com.orderly.host.plan.ExecutionPlan;
import com.orderly.host.plan.ExecutionStep;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanStatus;
import com.orderly.host.plan.PlanView;
import com.orderly.host.plan.StepStatus;
import com.orderly.host.system.SystemFeatureService;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划与持久化执行记录的统一确认、执行和回滚入口。
 *
 * <p>计划确认先校验启用状态、PREVIEW 状态、风险确认和全部目标，再创建 RUNNING
 * 执行记录；步骤严格顺序执行，首次失败停止后续步骤，并逆序回滚已完成步骤。
 * 持久化执行记录可在计划对象不再可用后继续查询和执行记录级回滚。</p>
 */
@Service
public class ExecutionService {

    private final PlanService plans;
    private final ExecutionJournal journal;
    private final ModuleControlCoordinator coordinator;
    private final SystemFeatureService features;
    private final ActionExecutionCore core;
    private final ExecutionRecordStore records;

    public ExecutionService(
            PlanService plans,
            ExecutionJournal journal,
            ModuleControlCoordinator coordinator,
            SystemFeatureService features,
            ActionExecutionCore core,
            ExecutionRecordStore records
    ) {
        this.plans = plans;
        this.journal = journal;
        this.coordinator = coordinator;
        this.features = features;
        this.core = core;
        this.records = records;
    }

    /**
     * 确认并执行一个旧版计划。
     *
     * <p>前置条件为计划功能开启、计划处于 PREVIEW、步骤非空、非只读计划已确认，
     * 且所有目标模块启用、动作参数有效。执行期间持有协调器执行许可，计划状态从
     * PREVIEW 迁移到 RUNNING，最终迁移到 COMPLETED、REJECTED 或
     * PARTIALLY_ROLLED_BACK；失败时返回结果而不向调用方抛出业务失败。</p>
     */
    public ExecutionRunResult confirm(String planId, boolean confirmed, String confirmation) {
        features.requirePlanningEnabled();
        ExecutionPlan plan = plans.require(planId);
        boolean controlPlan = plan.steps().stream().anyMatch(step ->
                core.isControlAction(step.moduleId(), step.actionId()));
        if (controlPlan && plan.steps().size() != 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "SYSTEM_ACTION_MUST_RUN_ALONE",
                    "系统治理 Action 必须单独成计划，不能与其他步骤混合"
            );
        }
        ModuleControlCoordinator.ExecutionLease permit = controlPlan
                ? coordinator.beginControlExecution()
                : coordinator.beginExecution();
        try (permit) {
            if (plan.status() != PlanStatus.PREVIEW) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PLAN_STATE_CONFLICT",
                        "计划当前状态为 " + plan.status() + "，不能再次确认"
                );
            }
            if (plan.steps().isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PLAN_NEEDS_CLARIFICATION",
                        "澄清问题不能执行；请重新描述目标"
                );
            }
            validateConfirmation(plan, confirmed);
            validateTargets(plan);

            Instant startedAt = Instant.now();
            List<ExecutionRecordStep> recordSteps = new ArrayList<>();
            for (ExecutionStep step : plan.steps()) {
                recordSteps.add(recordStep(step));
            }
            ExecutionRecord record = new ExecutionRecord(
                    plan.id(),
                    plan.goal(),
                    "PLAN",
                    "RUNNING",
                    recordSteps,
                    ExecutionRecord.aggregate(recordSteps, ExecutionRecord.StepPart.ARGUMENTS),
                    ExecutionRecord.aggregate(recordSteps, ExecutionRecord.StepPart.OUTPUT),
                    ExecutionRecord.aggregate(recordSteps, ExecutionRecord.StepPart.SNAPSHOT),
                    eventsFor(plan.id()),
                    startedAt,
                    startedAt,
                    null,
                    startedAt
            );
            records.save(record);

            plan.status(PlanStatus.RUNNING);
            journal.append(
                    "PLAN",
                    "PLAN",
                    plan.id(),
                    null,
                    null,
                    null,
                    "RUNNING",
                    "计划已确认，开始执行"
            );

            List<ExecutionStep> completed = new ArrayList<>();
            try {
                for (int index = 0; index < plan.steps().size(); index++) {
                    ExecutionStep step = plan.steps().get(index);
                    step.status(StepStatus.RUNNING);
                    try {
                        ActionExecutionCore.ActionResult result = core.execute(
                                plan.id(),
                                step.id(),
                                step.moduleId(),
                                step.actionId(),
                                step.arguments(),
                                "PLAN"
                        );
                        step.snapshot(result.snapshot());
                        step.output(result.output());
                        step.message(result.message());
                        step.status(StepStatus.COMPLETED);
                        completed.add(step);
                    } catch (ActionExecutionException exception) {
                        step.snapshot(exception.snapshot());
                        step.output(exception.output());
                        step.message(exception.getMessage());
                        if (exception.executed()) {
                            step.status(StepStatus.COMPLETED);
                            completed.add(step);
                        } else {
                            step.status(StepStatus.FAILED);
                        }
                        recordSteps.set(index, recordStep(step));
                        throw exception;
                    }
                    recordSteps.set(index, recordStep(step));
                    record = record.withChanges(
                            "RUNNING",
                            recordSteps,
                            eventsFor(plan.id()),
                            startedAt,
                            null
                    );
                    records.save(record);
                }

                plan.status(PlanStatus.COMPLETED);
                journal.append(
                        "PLAN",
                        "PLAN",
                        plan.id(),
                        null,
                        null,
                        null,
                        "COMPLETED",
                        "计划执行完成"
                );
                Instant completedAt = Instant.now();
                record = record.withChanges(
                        "COMPLETED",
                        recordSteps,
                        eventsFor(plan.id()),
                        startedAt,
                        completedAt
                );
                records.save(record);
                return new ExecutionRunResult(
                        "COMPLETED",
                        "COMPLETED",
                        "计划执行完成",
                        PlanView.from(plan)
                );
            } catch (RuntimeException exception) {
                String message = safeMessage(exception);
                plan.status(PlanStatus.REJECTED);
                journal.append(
                        "PLAN",
                        "PLAN",
                        plan.id(),
                        null,
                        null,
                        null,
                        "FAILED",
                        message
                );
                boolean rolledBack = rollbackSteps(
                        plan,
                        completed,
                        recordSteps,
                        "失败后自动回滚"
                );
                if (!rolledBack) {
                    plan.status(PlanStatus.PARTIALLY_ROLLED_BACK);
                }
                String recordStatus = rolledBack
                        ? "FAILED_ROLLED_BACK"
                        : "FAILED_PARTIALLY_ROLLED_BACK";
                Instant completedAt = Instant.now();
                record = record.withChanges(
                        recordStatus,
                        recordSteps,
                        eventsFor(plan.id()),
                        startedAt,
                        completedAt
                );
                records.save(record);
                return new ExecutionRunResult(
                        recordStatus,
                        recordStatus,
                        message,
                        PlanView.from(plan)
                );
            }
        }
    }

    /**
     * 对内存中已完成或部分回滚失败的旧版计划执行整单回滚。
     *
     * <p>只处理当前仍为 COMPLETED 的步骤，按逆序调用统一回滚核心；步骤跳过模块
     * 启用状态检查。全部成功时计划迁移到 ROLLED_BACK，否则保留为
     * PARTIALLY_ROLLED_BACK。</p>
     */
    public ExecutionRunResult rollback(String planId) {
        features.requirePlanningEnabled();
        ExecutionPlan plan = plans.require(planId);
        boolean controlPlan = plan.steps().stream().anyMatch(step ->
                core.isControlAction(step.moduleId(), step.actionId()));
        ModuleControlCoordinator.ExecutionLease permit = controlPlan
                ? coordinator.beginControlExecution()
                : coordinator.beginExecution();
        try (permit) {
            if (plan.status() != PlanStatus.COMPLETED
                    && plan.status() != PlanStatus.PARTIALLY_ROLLED_BACK) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "PLAN_NOT_ROLLBACKABLE",
                        "只有已完成或部分回滚失败的整单可以继续回滚"
                );
            }
            List<ExecutionStep> completed = plan.steps().stream()
                    .filter(step -> step.status() == StepStatus.COMPLETED)
                    .toList();
            ExecutionRecord record = records.find(plan.id()).orElse(null);
            List<ExecutionRecordStep> recordSteps = record == null
                    ? new ArrayList<>(recordSteps(plan))
                    : new ArrayList<>(record.steps());
            boolean success = rollbackSteps(
                    plan,
                    completed,
                    recordSteps,
                    "用户请求整单回滚"
            );
            plan.status(success ? PlanStatus.ROLLED_BACK : PlanStatus.PARTIALLY_ROLLED_BACK);
            journal.append(
                    "PLAN",
                    "PLAN",
                    plan.id(),
                    null,
                    null,
                    null,
                    plan.status().name(),
                    success ? "整单已回滚" : "整单回滚未完全成功，请检查事件记录"
            );
            if (record != null) {
                records.save(record.withChanges(
                        success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                        recordSteps,
                        eventsFor(plan.id()),
                        record.startedAt(),
                        Instant.now()
                ));
            }
            return new ExecutionRunResult(
                    success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                    success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                    success ? "整单已回滚" : "部分步骤回滚失败",
                    PlanView.from(plan)
            );
        }
    }

    /**
     * 从持久化执行记录恢复并执行整单回滚。
     *
     * <p>仅允许 COMPLETED、PARTIALLY_ROLLED_BACK 或
     * FAILED_PARTIALLY_ROLLED_BACK。逆序回滚 completed 步骤并保存新记录；
     * 若来源为 PLAN，还会同步仍驻留内存的计划步骤状态。</p>
     */
    public ExecutionRecord rollbackExecution(String executionId) {
        ExecutionRecord record = records.require(executionId);
        boolean controlExecution = record.steps().stream().anyMatch(step ->
                core.isControlAction(step.moduleId(), step.actionId()));
        ModuleControlCoordinator.ExecutionLease permit = controlExecution
                ? coordinator.beginControlExecution()
                : coordinator.beginExecution();
        try (permit) {
            ExecutionRollbackPolicy.Assessment rollback =
                    ExecutionRollbackPolicy.assess(record);
            if (!rollback.canRollback()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "EXECUTION_NOT_ROLLBACKABLE",
                        rollback.reason()
                );
            }
            List<ExecutionRecordStep> recordSteps = new ArrayList<>(record.steps());
            boolean success = rollbackRecordSteps(record, recordSteps, "用户请求执行记录回滚");
            ExecutionRecord saved = record.withChanges(
                    success ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK",
                    recordSteps,
                    eventsFor(executionId),
                    record.startedAt(),
                    Instant.now()
            );
            records.save(saved);
            syncPlanAfterRecordRollback(saved, success);
            return saved;
        }
    }

    /** 聚合整份计划最高风险；存在 WRITE 或 DELETE 且未确认时拒绝执行。 */
    private void validateConfirmation(ExecutionPlan plan, boolean confirmed) {
        RiskLevel highestRisk = highestRisk(plan.steps().stream()
                .map(ExecutionStep::risk)
                .toList());
        if (highestRisk != RiskLevel.READ_ONLY && !confirmed) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PLAN_CONFIRMATION_REQUIRED",
                    "计划包含写入或删除步骤，必须确认后才能执行",
                    Map.of("highestRisk", highestRisk.name())
            );
        }
    }

    /** 执行前重新解析所有目标并校验参数，防止预览后模块状态或契约快照失效。 */
    private void validateTargets(ExecutionPlan plan) {
        for (ExecutionStep step : plan.steps()) {
            ActionExecutionCore.ResolvedAction target = core.requirePlanTarget(
                    step.moduleId(),
                    step.actionId()
            );
            core.validateArguments(target.action(), step.arguments());
        }
    }

    /**
     * 逆序回滚内存计划中已完成的步骤并维护记录步骤。
     *
     * <p>单步失败不会中断后续回滚尝试；整体返回值表示是否全部成功。每次尝试都写入
     * 审计事件，部分失败会保留 ROLLBACK_FAILED 状态供用户继续处理。</p>
     */
    private boolean rollbackSteps(
            ExecutionPlan plan,
            List<ExecutionStep> steps,
            List<ExecutionRecordStep> recordSteps,
            String reason
    ) {
        boolean allSucceeded = true;
        journal.append("ROLLBACK", "PLAN", plan.id(), null, null, null, "RUNNING", reason);
        for (int index = steps.size() - 1; index >= 0; index--) {
            ExecutionStep step = steps.get(index);
            if (step.status() != StepStatus.COMPLETED) {
                continue;
            }
            try {
                core.rollback(
                        plan.id(),
                        step.id(),
                        step.moduleId(),
                        step.actionId(),
                        step.arguments(),
                        step.snapshot(),
                        step.output(),
                        "PLAN"
                );
                step.status(StepStatus.ROLLED_BACK);
                replaceRecordStep(recordSteps, step.id(), recordStep(step));
            } catch (RuntimeException exception) {
                allSucceeded = false;
                replaceRecordStep(
                        recordSteps,
                        step.id(),
                        withRecordStatus(recordStep(step), "ROLLBACK_FAILED", safeMessage(exception))
                );
            }
        }
        journal.append(
                "ROLLBACK",
                "PLAN",
                plan.id(),
                null,
                null,
                null,
                allSucceeded ? "COMPLETED" : "FAILED",
                allSucceeded ? "回滚完成" : "回滚部分失败，已保留审计记录"
        );
        return allSucceeded;
    }

    /** 从持久化步骤快照逆序回滚 completed 步骤，并更新记录步骤状态。 */
    private boolean rollbackRecordSteps(
            ExecutionRecord record,
            List<ExecutionRecordStep> steps,
            String reason
    ) {
        boolean allSucceeded = true;
        journal.append(
                "ROLLBACK",
                record.origin(),
                record.executionId(),
                null,
                null,
                null,
                "RUNNING",
                reason
        );
        for (int index = steps.size() - 1; index >= 0; index--) {
            ExecutionRecordStep step = steps.get(index);
            if (!ExecutionRollbackPolicy.isRollbackCandidate(step)) {
                continue;
            }
            try {
                core.rollback(
                        record.executionId(),
                        step.stepId(),
                        step.moduleId(),
                        step.actionId(),
                        step.arguments(),
                        step.snapshot(),
                        step.output(),
                        record.origin()
                );
                steps.set(index, withRecordStatus(step, "ROLLED_BACK", step.message()));
            } catch (RuntimeException exception) {
                allSucceeded = false;
                steps.set(index, withRecordStatus(
                        step,
                        "ROLLBACK_FAILED",
                        safeMessage(exception)
                ));
            }
        }
        journal.append(
                "ROLLBACK",
                record.origin(),
                record.executionId(),
                null,
                null,
                null,
                allSucceeded ? "COMPLETED" : "FAILED",
                allSucceeded ? "回滚完成" : "回滚部分失败，已保留审计记录"
        );
        return allSucceeded;
    }

    /** 将持久化执行记录的回滚状态同步到仍存在内存中的同 ID 计划。 */
    private void syncPlanAfterRecordRollback(ExecutionRecord record, boolean success) {
        if (!"PLAN".equals(record.origin())) {
            return;
        }
        plans.find(record.executionId()).ifPresent(plan -> {
            Map<String, String> statusByStep = record.steps().stream().collect(
                    java.util.stream.Collectors.toMap(
                            ExecutionRecordStep::stepId,
                            ExecutionRecordStep::status
                    )
            );
            for (ExecutionStep step : plan.steps()) {
                String status = statusByStep.get(step.id());
                if ("ROLLED_BACK".equals(status)) {
                    step.status(StepStatus.ROLLED_BACK);
                } else if ("ROLLBACK_FAILED".equals(status)) {
                    step.status(StepStatus.FAILED);
                }
            }
            plan.status(success ? PlanStatus.ROLLED_BACK : PlanStatus.PARTIALLY_ROLLED_BACK);
        });
    }

    /** 将内存计划步骤转换为持久化步骤快照。 */
    private List<ExecutionRecordStep> recordSteps(ExecutionPlan plan) {
        return plan.steps().stream().map(this::recordStep).toList();
    }

    /** 转换单个计划步骤，复制其参数、风险、快照、输出和当前状态。 */
    private ExecutionRecordStep recordStep(ExecutionStep step) {
        return new ExecutionRecordStep(
                step.id(),
                step.moduleId(),
                step.actionId(),
                step.title(),
                step.risk(),
                step.arguments(),
                step.status().name(),
                step.snapshot(),
                step.output(),
                step.message()
        );
    }

    /** 替换记录步骤状态和摘要，同时保留快照与输出。 */
    private ExecutionRecordStep withRecordStatus(
            ExecutionRecordStep step,
            String status,
            String message
    ) {
        return new ExecutionRecordStep(
                step.stepId(),
                step.moduleId(),
                step.actionId(),
                step.title(),
                step.risk(),
                step.arguments(),
                status,
                step.snapshot(),
                step.output(),
                message
        );
    }

    /** 按步骤 ID 替换执行记录中的对应项。 */
    private void replaceRecordStep(
            List<ExecutionRecordStep> recordSteps,
            String stepId,
            ExecutionRecordStep replacement
    ) {
        for (int index = 0; index < recordSteps.size(); index++) {
            if (recordSteps.get(index).stepId().equals(stepId)) {
                recordSteps.set(index, replacement);
                return;
            }
        }
    }

    /** 从内存审计日志中筛选当前计划或执行的事件快照。 */
    private List<ExecutionEvent> eventsFor(String executionId) {
        return journal.snapshot().stream()
                .filter(event -> executionId.equals(event.planId()))
                .toList();
    }

    /** 根据 RiskLevel 的自然顺序计算批次最高风险。 */
    private RiskLevel highestRisk(List<RiskLevel> risks) {
        RiskLevel highest = RiskLevel.READ_ONLY;
        for (RiskLevel risk : risks) {
            if (risk.ordinal() > highest.ordinal()) {
                highest = risk;
            }
        }
        return highest;
    }

    /** 返回可审计的非空异常摘要。 */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }
}
