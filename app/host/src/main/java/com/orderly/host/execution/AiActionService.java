package com.orderly.host.execution;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 受控 AI 直执入口；执行前与计划路径共用同一 Action 核心担保。 */
@Service
public class AiActionService {

    private final ActionExecutionCore core;
    private final ExecutionRecordStore records;
    private final ExecutionJournal journal;
    private final ModuleControlCoordinator coordinator;

    public AiActionService(
            ActionExecutionCore core,
            ExecutionRecordStore records,
            ExecutionJournal journal,
            ModuleControlCoordinator coordinator
    ) {
        this.core = core;
        this.records = records;
        this.journal = journal;
        this.coordinator = coordinator;
    }

    public ExecutionRecord execute(boolean confirmed, List<ActionCall> actions) {
        return executeBatch("AI_DIRECT", confirmed, actions, true);
    }

    /** 模块自有 UI 发起的单动作入口，使用 MODULE_CONTROL 来源并复用同一执行核心。 */
    public ExecutionRecord executeModuleControl(
            String moduleId,
            String actionId,
            Map<String, Object> arguments,
            boolean confirmed
    ) {
        if (moduleId == null || moduleId.isBlank() || actionId == null || actionId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTIONS",
                    "moduleId 与 actionId 不能为空"
            );
        }
        return executeBatch(
                "MODULE_CONTROL",
                confirmed,
                List.of(new ActionCall(moduleId, actionId, arguments)),
                false
        );
    }

    private ExecutionRecord executeBatch(
            String origin,
            boolean confirmed,
            List<ActionCall> actions,
            boolean aiOnly
    ) {
        if (actions == null || actions.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTIONS",
                    "actions 必须是非空数组"
            );
        }

        List<ActionExecutionCore.ResolvedAction> targets = new ArrayList<>();
        RiskLevel highestRisk = RiskLevel.READ_ONLY;
        for (ActionCall action : actions) {
            if (action == null
                    || action.moduleId() == null
                    || action.moduleId().isBlank()
                    || action.actionId() == null
                    || action.actionId().isBlank()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ACTIONS",
                        "每个 action 必须声明 moduleId 和 actionId"
                );
            }
            ActionExecutionCore.ResolvedAction target = aiOnly
                    ? core.requireAiTarget(action.moduleId(), action.actionId())
                    : core.requirePlanTarget(action.moduleId(), action.actionId());
            targets.add(target);
            if (target.risk().ordinal() > highestRisk.ordinal()) {
                highestRisk = target.risk();
            }
        }
        if (highestRisk != RiskLevel.READ_ONLY && !confirmed) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONFIRMATION_REQUIRED",
                    "动作数组包含写入或删除动作，必须确认后才能执行",
                    Map.of("highestRisk", highestRisk.name())
            );
        }

        try (var permit = coordinator.beginExecution()) {
            String executionId = UUID.randomUUID().toString();
            Instant startedAt = Instant.now();
            List<ExecutionRecordStep> steps = new ArrayList<>();
            for (int index = 0; index < actions.size(); index++) {
                ActionCall action = actions.get(index);
                steps.add(new ExecutionRecordStep(
                        "a" + (index + 1),
                        action.moduleId(),
                        action.actionId(),
                        targets.get(index).action().title(),
                        targets.get(index).risk(),
                        action.arguments(),
                        "WAITING",
                        Map.of(),
                        Map.of(),
                        ""
                ));
            }
            ExecutionRecord record = new ExecutionRecord(
                    executionId,
                    origin,
                    "RUNNING",
                    steps,
                    ExecutionRecord.aggregate(steps, ExecutionRecord.StepPart.ARGUMENTS),
                    ExecutionRecord.aggregate(steps, ExecutionRecord.StepPart.OUTPUT),
                    ExecutionRecord.aggregate(steps, ExecutionRecord.StepPart.SNAPSHOT),
                    eventsFor(executionId),
                    startedAt,
                    startedAt,
                    null,
                    startedAt
            );
            records.save(record);
            journal.append(
                    "EXECUTION",
                    origin,
                    executionId,
                    null,
                    null,
                    null,
                    "RUNNING",
                    "AI 直执动作数组开始执行"
            );

            List<Map<String, Object>> completedOutputs = new ArrayList<>();
            List<ExecutionRecordStep> completed = new ArrayList<>();
            try {
                for (int index = 0; index < actions.size(); index++) {
                    ActionCall action = actions.get(index);
                    ExecutionRecordStep waiting = steps.get(index);
                    Map<String, Object> resolvedArguments;
                    try {
                        resolvedArguments = StructuredActionReferenceResolver.resolve(
                                action.arguments(),
                                completedOutputs
                        );
                    } catch (RuntimeException exception) {
                        steps.set(index, withStep(
                                waiting,
                                waiting.arguments(),
                                "FAILED",
                                Map.of(),
                                Map.of(),
                                safeMessage(exception)
                        ));
                        throw exception;
                    }
                    ExecutionRecordStep running = withStep(
                            waiting,
                            resolvedArguments,
                            "RUNNING",
                            Map.of(),
                            Map.of(),
                            ""
                    );
                    steps.set(index, running);
                    record = record.withChanges(
                            "RUNNING",
                            steps,
                            eventsFor(executionId),
                            startedAt,
                            null
                    );
                    records.save(record);

                    try {
                        ActionExecutionCore.ActionResult result = core.execute(
                                executionId,
                                running.stepId(),
                                running.moduleId(),
                                running.actionId(),
                                resolvedArguments,
                                origin
                        );
                        ExecutionRecordStep completedStep = withStep(
                                running,
                                resolvedArguments,
                                "COMPLETED",
                                result.snapshot(),
                                result.output(),
                                result.message()
                        );
                        steps.set(index, completedStep);
                        completed.add(completedStep);
                        completedOutputs.add(result.output());
                    } catch (ActionExecutionException exception) {
                        ExecutionRecordStep failedStep = withStep(
                                running,
                                resolvedArguments,
                                exception.executed() ? "COMPLETED" : "FAILED",
                                exception.snapshot(),
                                exception.output(),
                                exception.getMessage()
                        );
                        steps.set(index, failedStep);
                        if (exception.executed()) {
                            completed.add(failedStep);
                            completedOutputs.add(exception.output());
                        }
                        throw exception;
                    }
                    record = record.withChanges(
                            "RUNNING",
                            steps,
                            eventsFor(executionId),
                            startedAt,
                            null
                    );
                    records.save(record);
                }

                journal.append(
                        "EXECUTION",
                        origin,
                        executionId,
                        null,
                        null,
                        null,
                        "COMPLETED",
                        "AI 直执动作数组执行完成"
                );
                return records.save(record.withChanges(
                        "COMPLETED",
                        steps,
                        eventsFor(executionId),
                        startedAt,
                        Instant.now()
                ));
            } catch (RuntimeException exception) {
                String message = safeMessage(exception);
                journal.append(
                        "EXECUTION",
                        origin,
                        executionId,
                        null,
                        null,
                        null,
                        "FAILED",
                        message
                );
                boolean rolledBack = rollbackCompleted(origin, executionId, steps, completed);
                String status = rolledBack
                        ? "FAILED_ROLLED_BACK"
                        : "FAILED_PARTIALLY_ROLLED_BACK";
                return records.save(record.withChanges(
                        status,
                        steps,
                        eventsFor(executionId),
                        startedAt,
                        Instant.now()
                ));
            }
        }
    }

    private boolean rollbackCompleted(
            String origin,
            String executionId,
            List<ExecutionRecordStep> steps,
            List<ExecutionRecordStep> completed
    ) {
        boolean allSucceeded = true;
        journal.append(
                "ROLLBACK",
                origin,
                executionId,
                null,
                null,
                null,
                "RUNNING",
                "失败后自动回滚已完成动作"
        );
        for (int index = completed.size() - 1; index >= 0; index--) {
            ExecutionRecordStep completedStep = completed.get(index);
            try {
                core.rollback(
                        executionId,
                        completedStep.stepId(),
                        completedStep.moduleId(),
                        completedStep.actionId(),
                        completedStep.arguments(),
                        completedStep.snapshot(),
                        completedStep.output(),
                        origin
                );
                replace(
                        steps,
                        completedStep.stepId(),
                        withStep(
                                completedStep,
                                completedStep.arguments(),
                                "ROLLED_BACK",
                                completedStep.snapshot(),
                                completedStep.output(),
                                completedStep.message()
                        )
                );
            } catch (RuntimeException exception) {
                allSucceeded = false;
                replace(
                        steps,
                        completedStep.stepId(),
                        withStep(
                                completedStep,
                                completedStep.arguments(),
                                "ROLLBACK_FAILED",
                                completedStep.snapshot(),
                                completedStep.output(),
                                safeMessage(exception)
                        )
                );
            }
        }
        journal.append(
                "ROLLBACK",
                origin,
                executionId,
                null,
                null,
                null,
                allSucceeded ? "COMPLETED" : "FAILED",
                allSucceeded ? "自动回滚完成" : "自动回滚部分失败"
        );
        return allSucceeded;
    }

    private void replace(
            List<ExecutionRecordStep> steps,
            String stepId,
            ExecutionRecordStep replacement
    ) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).stepId().equals(stepId)) {
                steps.set(index, replacement);
                return;
            }
        }
    }

    private ExecutionRecordStep withStep(
            ExecutionRecordStep step,
            Map<String, Object> arguments,
            String status,
            Map<String, Object> snapshot,
            Map<String, Object> output,
            String message
    ) {
        return new ExecutionRecordStep(
                step.stepId(),
                step.moduleId(),
                step.actionId(),
                step.title(),
                step.risk(),
                arguments,
                status,
                snapshot,
                output,
                message
        );
    }

    private List<com.orderly.host.event.ExecutionEvent> eventsFor(String executionId) {
        return journal.snapshot().stream()
                .filter(event -> executionId.equals(event.planId()))
                .toList();
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }

    public record ActionCall(
            String moduleId,
            String actionId,
            Map<String, Object> arguments
    ) {
        public ActionCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
