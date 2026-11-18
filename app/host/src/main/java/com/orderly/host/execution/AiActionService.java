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

/**
 * AI Action 数组的预览、直执和独立 AI 计划执行入口。
 *
 * <p>批量执行最多接受 50 项，先解析全部目标并汇总最高风险，再要求非只读批次获得
 * 确认。执行严格保持数组顺序，后续参数只能引用此前已完成输出；任一 Action 失败
 * 后不再执行剩余项，并按完成顺序逆序回滚。所有路径复用
 * {@link ActionExecutionCore} 的风险、快照、审计和回滚担保。</p>
 */
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

    /** 以 AI 直执来源执行 Action 数组，调用方传入批次确认状态。 */
    public ExecutionRecord execute(boolean confirmed, List<ActionCall> actions) {
        return executeBatch("AI_DIRECT", confirmed, actions, true);
    }

    /**
     * 校验并汇总 Action 数组，不产生任何执行副作用。
     *
     * <p>逐项执行 AI 目标检查与参数校验，并按 READ_ONLY、WRITE、DELETE 的顺序
     * 汇总最高风险；空数组、超过 50 项或缺少模块/动作标识会直接拒绝。</p>
     */
    public Preview preview(List<ActionCall> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTIONS",
                    "actions 必须是非空数组"
            );
        }
        if (actions.size() > 50) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TOO_MANY_ACTIONS",
                    "单次最多允许 50 个 Action"
            );
        }
        List<PreviewStep> steps = new ArrayList<>();
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
            ActionExecutionCore.ResolvedAction target = core.requireAiTarget(
                    action.moduleId(),
                    action.actionId()
            );
            core.validateArguments(target.action(), action.arguments());
            if (target.risk().ordinal() > highestRisk.ordinal()) {
                highestRisk = target.risk();
            }
            steps.add(new PreviewStep(
                    action.moduleId(),
                    action.actionId(),
                    target.action().title(),
                    target.action().description(),
                    target.risk(),
                    action.arguments()
            ));
        }
        return new Preview(List.copyOf(actions), List.copyOf(steps), highestRisk);
    }

    /**
     * 用户已明确要求执行时由宿主调用，批次按已确认处理。
     *
     * <p>“已确认”只豁免本方法的二次确认；AI 可调用、模块启用、快照和回滚仍由
     * 统一执行核心保证。</p>
     */
    public ExecutionRecord executeAiDirect(List<ActionCall> actions) {
        return executeBatch("AI_DIRECT", true, actions, true);
    }

    /** 独立 AI 计划确认后执行，来源记为 AI_PLAN，并复用同一数组执行状态机。 */
    public ExecutionRecord executeAiPlan(List<ActionCall> actions) {
        return executeBatch("AI_PLAN", true, actions, true);
    }

    /**
     * 模块自有 UI 发起的单动作入口。
     *
     * <p>来源固定为 MODULE_CONTROL，不应用 AI 可调用开关；模块启用、参数、快照、
     * 审计与回滚仍由统一执行核心处理。</p>
     */
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

    /**
     * 执行一个有序 Action 批次。
     *
     * <p>预检阶段解析全部目标和最高风险，非只读批次需要确认；随后创建 RUNNING
     * 执行记录。运行阶段逐项解析结构化引用、保存 RUNNING 步骤、调用统一核心并
     * 持久化已完成输出。首次失败停止后续项，并逆序回滚已完成步骤。最终记录为
     * COMPLETED、FAILED_ROLLED_BACK 或 FAILED_PARTIALLY_ROLLED_BACK。</p>
     */
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
        if (actions.size() > 50) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TOO_MANY_ACTIONS",
                    "单次最多允许 50 个 Action"
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
                    actionSourceLabel(origin) + "动作数组开始执行"
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
                        actionSourceLabel(origin) + "动作数组执行完成"
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

    /** 逆序回滚已完成步骤；单步回滚失败会继续尝试其余步骤并返回整体是否全部成功。 */
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

    /** 用步骤 ID 替换执行记录中的对应步骤。 */
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

    /** 复制步骤元数据并替换本次状态、快照、输出和摘要。 */
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

    /** 从内存审计日志中筛选当前执行的事件快照。 */
    private List<com.orderly.host.event.ExecutionEvent> eventsFor(String executionId) {
        return journal.snapshot().stream()
                .filter(event -> executionId.equals(event.planId()))
                .toList();
    }

    /** 返回可审计的非空异常摘要。 */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }

    /** 仅为 AI 计划与 AI 直执生成日志中的来源文案。 */
    private String actionSourceLabel(String origin) {
        return "AI_PLAN".equals(origin) ? "AI 计划" : "AI 直执";
    }

    /**
     * AI 提交的一项 Action 调用。
     *
     * <p>构造时将参数做不可变顶层复制，不在此处解释结构化引用或判断风险。</p>
     */
    public record ActionCall(
            String moduleId,
            String actionId,
            Map<String, Object> arguments
    ) {
        public ActionCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 预览中的单步元数据，不包含快照、输出或执行副作用。 */
    public record PreviewStep(
            String moduleId,
            String actionId,
            String title,
            String description,
            RiskLevel risk,
            Map<String, Object> arguments
    ) {
        public PreviewStep {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 已校验 Action 批次及最高风险，供确认策略使用。 */
    public record Preview(
            List<ActionCall> actions,
            List<PreviewStep> steps,
            RiskLevel highestRisk
    ) {
        public Preview {
            actions = List.copyOf(actions);
            steps = List.copyOf(steps);
        }
    }
}
