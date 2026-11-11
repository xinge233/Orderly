package com.orderly.host.plan;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.planner.LocalRulePlanner;
import com.orderly.host.planner.PlannerResult;
import com.orderly.host.planner.PlannerStep;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 计划生成、保存和前置校验入口。 */
@Service
public class PlanService {

    private final LocalRulePlanner planner;
    private final ContractCatalog catalog;
    private final ConcurrentMap<String, ExecutionPlan> plans = new ConcurrentHashMap<>();

    public PlanService(LocalRulePlanner planner, ContractCatalog catalog) {
        this.planner = planner;
        this.catalog = catalog;
    }

    public PlanView create(String goal) {
        PlannerResult result = planner.draft(goal);
        List<ExecutionStep> steps = new ArrayList<>();
        String previous = null;
        for (int index = 0; index < result.steps().size(); index++) {
            PlannerStep raw = result.steps().get(index);
            Optional<ActionContract> registered = catalog.findAction(raw.actionId());
            if (registered.isEmpty()) {
                return saveQuestion(
                        goal,
                        "当前没有已安装模块提供动作“" + raw.actionId()
                                + "”。请安装对应本地模块后重试；未生成可执行计划。"
                );
            }
            ActionContract action = registered.orElseThrow();
            validateArguments(action, raw.arguments());
            String id = "s" + (index + 1);
            steps.add(new ExecutionStep(
                    id,
                    catalog.moduleIdForAction(action.id()),
                    action.id(),
                    action.title(),
                    action.description(),
                    raw.arguments(),
                    action.risk(),
                    action.confirmationPhrase(),
                    previous == null ? List.of() : List.of(previous)
            ));
            previous = id;
        }
        validateDag(steps);

        String id = java.util.UUID.randomUUID().toString();
        ExecutionPlan plan = new ExecutionPlan(
                id,
                goal == null ? "" : goal.trim(),
                result.kind(),
                result.message(),
                result.assumptions(),
                result.impact(),
                result.confidence(),
                steps,
                Instant.now()
        );
        plans.put(id, plan);
        return PlanView.from(plan);
    }

    private PlanView saveQuestion(String goal, String message) {
        String id = java.util.UUID.randomUUID().toString();
        ExecutionPlan plan = new ExecutionPlan(
                id,
                goal == null ? "" : goal.trim(),
                "QUESTION",
                message,
                List.of("本地规则计划器未连接外部模型。", "未安装的模块不会被推断或执行。"),
                "未产生任何变更。",
                0.2,
                List.of(),
                Instant.now()
        );
        plans.put(id, plan);
        return PlanView.from(plan);
    }

    public ExecutionPlan require(String id) {
        ExecutionPlan plan = plans.get(id);
        if (plan == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在或已过期");
        }
        return plan;
    }

    public List<PlanView> list() {
        return plans.values().stream()
                .sorted(java.util.Comparator.comparing(ExecutionPlan::createdAt).reversed())
                .map(PlanView::from)
                .toList();
    }

    private void validateArguments(ActionContract action, Map<String, Object> arguments) {
        Set<String> allowed = new HashSet<>();
        for (ParameterContract parameter : action.parameters()) {
            allowed.add(parameter.name());
            Object value = arguments.get(parameter.name());
            if (parameter.required() && (value == null || value.toString().isBlank())) {
                throw new IllegalArgumentException("动作 " + action.id() + " 缺少必填参数：" + parameter.name());
            }
            if (value != null) {
                validateType(action.id(), parameter, value);
            }
        }
        for (String supplied : arguments.keySet()) {
            if (!allowed.contains(supplied)) {
                throw new IllegalArgumentException("动作 " + action.id() + " 包含契约外参数：" + supplied);
            }
        }
    }

    private void validateType(String actionId, ParameterContract parameter, Object value) {
        boolean valid = switch (parameter.type()) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Integer || value instanceof Long;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "动作 " + actionId + " 参数 " + parameter.name() + " 类型应为 " + parameter.type()
            );
        }
    }

    private void validateDag(List<ExecutionStep> steps) {
        Map<String, ExecutionStep> byId = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> next = new HashMap<>();
        for (ExecutionStep step : steps) {
            if (byId.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("计划包含重复步骤 ID");
            }
            inDegree.put(step.id(), 0);
            next.put(step.id(), new ArrayList<>());
        }
        for (ExecutionStep step : steps) {
            for (String dependency : step.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("依赖步骤不存在：" + dependency);
                }
                inDegree.compute(step.id(), (key, value) -> value + 1);
                next.get(dependency).add(step.id());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String successor : next.get(id)) {
                int degree = inDegree.compute(successor, (key, value) -> value - 1);
                if (degree == 0) {
                    ready.addLast(successor);
                }
            }
        }
        if (visited != steps.size()) {
            throw new IllegalArgumentException("计划依赖存在环");
        }
    }
}
