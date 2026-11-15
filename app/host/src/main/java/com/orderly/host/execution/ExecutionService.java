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

/** 计划与持久化执行记录的统一确认、回滚入口。 */
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

    public ExecutionRunResult confirm(String planId, boolean confirmed, String confirmation) {
        features.requirePlanningEnabled();
        ExecutionPlan plan = plans.require(planId);
        try (var permit = coordinator.beginExecution()) {
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

    public ExecutionRunResult rollback(String planId) {
        features.requirePlanningEnabled();
        ExecutionPlan plan = plans.require(planId);
        try (var permit = coordinator.beginExecution()) {
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

    public ExecutionRecord rollbackExecution(String executionId) {
        ExecutionRecord record = records.require(executionId);
        try (var permit = coordinator.beginExecution()) {
            if (!List.of("COMPLETED", "PARTIALLY_ROLLED_BACK", "FAILED_PARTIALLY_ROLLED_BACK")
                    .contains(record.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "EXECUTION_NOT_ROLLBACKABLE",
                        "执行记录当前状态不允许回滚：" + record.status()
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

    private void validateTargets(ExecutionPlan plan) {
        for (ExecutionStep step : plan.steps()) {
            ActionExecutionCore.ResolvedAction target = core.requirePlanTarget(
                    step.moduleId(),
                    step.actionId()
            );
            core.validateArguments(target.action(), step.arguments());
        }
    }

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
            if (!"COMPLETED".equals(step.status())) {
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

    private List<ExecutionRecordStep> recordSteps(ExecutionPlan plan) {
        return plan.steps().stream().map(this::recordStep).toList();
    }

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

    private List<ExecutionEvent> eventsFor(String executionId) {
        return journal.snapshot().stream()
                .filter(event -> executionId.equals(event.planId()))
                .toList();
    }

    private RiskLevel highestRisk(List<RiskLevel> risks) {
        RiskLevel highest = RiskLevel.READ_ONLY;
        for (RiskLevel risk : risks) {
            if (risk.ordinal() > highest.ordinal()) {
                highest = risk;
            }
        }
        return highest;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }
}
