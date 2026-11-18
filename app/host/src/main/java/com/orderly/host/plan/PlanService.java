package com.orderly.host.plan;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.execution.ActionExecutionCore;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.planner.LocalRulePlanner;
import com.orderly.host.planner.PlannerResult;
import com.orderly.host.planner.PlannerStep;
import com.orderly.host.system.SystemFeatureService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 旧版计划的内存生成、保存和前置校验入口。
 *
 * <p>服务只在计划功能开启时调用本地规则计划器，把契约中已安装且启用的业务 Action
 * 转换为可审查步骤。这里不会执行 Action；确认、快照、回滚和审计由执行服务负责。
 * 计划对象仅保存在当前进程内，重启后不会恢复。</p>
 */
@Service
public class PlanService {

    private final LocalRulePlanner planner;
    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final ModuleControlCoordinator coordinator;
    private final SystemFeatureService features;
    private final ActionExecutionCore executionCore;
    private final ConcurrentMap<String, ExecutionPlan> plans = new ConcurrentHashMap<>();

    /** 注入计划器、契约索引、模块治理、功能开关和执行参数校验依赖。 */
    public PlanService(
            LocalRulePlanner planner,
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            ModuleControlCoordinator coordinator,
            SystemFeatureService features,
            ActionExecutionCore executionCore
    ) {
        this.planner = planner;
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.coordinator = coordinator;
        this.features = features;
        this.executionCore = executionCore;
    }

    /**
     * 在计划功能开启时为一个目标创建新的预览计划。
     *
     * <p>读取通过模块治理协调器执行，避免与模块启停或刷新提交并发。成功时计划会
     * 写入当前进程内存并返回视图；功能关闭时抛出冲突错误。重复调用即使目标相同也会
     * 生成不同计划，因此该方法不是幂等操作，但不会执行任何真实 Action。</p>
     */
    public PlanView create(String goal) {
        features.requirePlanningEnabled();
        return coordinator.read(() -> createEnabled(goal));
    }

    /**
     * 在已持有模块治理读取许可时完成规则匹配和契约校验。
     *
     * <p>每一步必须对应已安装、已启用的业务 Action；旧版计划不应用模块治理层的
     * Action AI 动态开关。参数先由统一执行核心验证，再串联为无环依赖链。无法匹配的动作转换为 {@code QUESTION} 计划，
     * 已匹配但参数或 DAG 非法的结果直接失败。成功或澄清结果都会写入内存计划表。</p>
     */
    private PlanView createEnabled(String goal) {
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
            String moduleId = catalog.moduleIdForAction(action.id());
            if (catalog.isSystemModule(moduleId) || !moduleControl.isEnabled(moduleId)) {
                return saveQuestion(
                        goal,
                        "模块“" + moduleId + "”当前已停用。请启用模块后重试；未生成可执行计划。"
                );
            }
            executionCore.validateArguments(action, raw.arguments());
            String id = "s" + (index + 1);
            steps.add(new ExecutionStep(
                    id,
                    moduleId,
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

    /**
     * 保存一个不可执行的澄清结果。
     *
     * <p>结果使用 {@code QUESTION} 类型和空步骤，假设中明确标注本地规则计划器未连接
     * 外部模型。方法只写入计划表，不产生工作区或模块状态变更。</p>
     */
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

    /**
     * 读取仍存在于当前进程中的计划。
     *
     * <p>缺失或重启后失效的计划抛出 404 业务异常。返回对象是宿主内部可变实例，只应
     * 在持有相应治理/执行协调许可的宿主服务中使用。</p>
     */
    public ExecutionPlan require(String id) {
        ExecutionPlan plan = plans.get(id);
        if (plan == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在或已过期");
        }
        return plan;
    }

    /** 按 ID 查找进程内计划；不存在时返回空，不创建占位计划。 */
    public Optional<ExecutionPlan> find(String id) {
        return Optional.ofNullable(plans.get(id));
    }

    /**
     * 返回按创建时间倒序排列的计划视图。
     *
     * <p>方法读取并发映射的当前值，不修改计划或状态。并发创建期间得到的是近实时集合，
     * 不承诺跨调用的一致快照。</p>
     */
    public List<PlanView> list() {
        return plans.values().stream()
                .sorted(java.util.Comparator.comparing(ExecutionPlan::createdAt).reversed())
                .map(PlanView::from)
                .toList();
    }

    /**
     * 校验步骤 ID 唯一、依赖存在且图无环。
     *
     * <p>该方法只构建临时图，不修改步骤或计划。重复 ID、悬空依赖或检测到环时抛出
     * 参数异常，由调用链拒绝保存该计划。</p>
     */
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
