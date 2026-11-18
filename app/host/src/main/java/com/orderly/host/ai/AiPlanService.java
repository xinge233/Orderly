package com.orderly.host.ai;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.modulecontrol.InterfaceUsageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 独立于旧版计划功能的 AI 计划提案与确认入口。 */
@Service
public class AiPlanService {

    private static final Duration PLAN_TTL = Duration.ofMinutes(30);

    private final AiActionService actions;
    private final InterfaceUsageRegistry usage;
    private final ExecutionJournal journal;
    private final Map<String, StoredPlan> plans = new ConcurrentHashMap<>();

    public AiPlanService(
            AiActionService actions,
            InterfaceUsageRegistry usage,
            ExecutionJournal journal
    ) {
        this.actions = actions;
        this.usage = usage;
        this.journal = journal;
    }

    public AiContracts.Plan create(String goal, List<AiContracts.Action> requestedActions) {
        return usage.withActionLock(() -> createLocked(goal, requestedActions));
    }

    private AiContracts.Plan createLocked(
            String goal,
            List<AiContracts.Action> requestedActions
    ) {
        pruneExpiredPlans(Instant.now());
        List<AiActionService.ActionCall> calls = requestedActions.stream()
                .map(action -> new AiActionService.ActionCall(
                        action.moduleId(),
                        action.actionId(),
                        action.arguments()
                ))
                .toList();
        AiActionService.Preview preview = actions.preview(calls);
        String planId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<AiContracts.PlanStep> steps = new ArrayList<>();
        for (int index = 0; index < preview.steps().size(); index++) {
            AiActionService.PreviewStep step = preview.steps().get(index);
            steps.add(new AiContracts.PlanStep(
                    "s" + (index + 1),
                    step.moduleId(),
                    step.actionId(),
                    step.title(),
                    step.description(),
                    step.risk(),
                    step.arguments()
            ));
        }
        AiContracts.Plan plan = new AiContracts.Plan(
                planId,
                String.valueOf(goal == null ? "" : goal.trim()),
                "PREVIEW",
                preview.highestRisk(),
                steps,
                now,
                now.plus(PLAN_TTL)
        );
        plans.put(planId, new StoredPlan(plan, calls, now.plus(PLAN_TTL)));
        usage.registerPlan(
                planId,
                now.plus(PLAN_TTL),
                calls.stream()
                        .map(action -> new InterfaceUsageRegistry.ActionRef(
                                action.moduleId(),
                                action.actionId()
                        ))
                        .toList()
        );
        try {
            journal.append(
                    "AI_PLAN",
                    "AI_PLAN",
                    planId,
                    null,
                    null,
                    null,
                    "PREVIEW",
                    "独立 AI 计划已生成，引用接口进入使用中"
            );
        } catch (RuntimeException exception) {
            plans.remove(planId);
            usage.removePlan(planId);
            throw exception;
        }
        return plan;
    }

    public synchronized ExecutionRecord confirm(String planId) {
        Instant now = Instant.now();
        pruneExpiredPlans(now);
        StoredPlan stored = plans.get(planId);
        if (stored == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "AI_PLAN_NOT_FOUND",
                    "AI 计划不存在或已过期"
            );
        }
        if (!now.isBefore(stored.expiresAt())) {
            plans.remove(planId);
            throw new ApiException(
                    HttpStatus.GONE,
                    "AI_PLAN_EXPIRED",
                    "AI 计划已过期，请重新生成"
            );
        }
        if (!"PREVIEW".equals(stored.plan().status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AI_PLAN_ALREADY_CONFIRMED",
                    "AI 计划已经确认，不能重复执行"
            );
        }
        try (var ignored = usage.beginPlanExecution(planId)) {
            plans.put(planId, stored.withStatus("CONFIRMING"));
            try {
                ExecutionRecord record = actions.executeAiPlan(stored.actions());
                plans.put(planId, stored.withStatus(record.status()));
                return record;
            } catch (RuntimeException exception) {
                plans.put(planId, stored.withStatus("FAILED"));
                throw exception;
            }
        }
    }

    private void pruneExpiredPlans(Instant now) {
        List<String> expired = plans.entrySet().stream()
                .filter(entry -> !now.isBefore(entry.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(planId -> {
            plans.remove(planId);
            usage.removePlan(planId);
        });
    }

    private record StoredPlan(
            AiContracts.Plan plan,
            List<AiActionService.ActionCall> actions,
            Instant expiresAt
    ) {
        private StoredPlan {
            actions = List.copyOf(actions);
        }

        private StoredPlan withStatus(String status) {
            return new StoredPlan(
                    new AiContracts.Plan(
                            plan.planId(),
                            plan.goal(),
                            status,
                            plan.highestRisk(),
                            plan.steps(),
                            plan.createdAt(),
                            plan.expiresAt()
                    ),
                    actions,
                    expiresAt
            );
        }
    }
}
