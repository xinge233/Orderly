package com.orderly.host.modulecontrol;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 记录未执行独立 AI 计划对 Action 的引用，以及当前正在执行的 Action。
 *
 * <p>该注册表只保存进程内短时运行态，不持久化。计划引用在过期时间到达后清理，
 * 正在执行的 Action 使用引用计数，许可关闭时释放。旧版计划不参与占用判断。</p>
 */
@Component
public class InterfaceUsageRegistry {

    private final Map<String, PlanUsage> plans = new HashMap<>();
    private final Map<String, Integer> activeActions = new HashMap<>();

    /**
     * 登记一份尚未执行的独立 AI 计划及其 Action 集合。
     *
     * <p>同一 planId 再次登记会覆盖旧占用；重复 Action 在集合内去重。该操作不持久化。</p>
     */
    public synchronized void registerPlan(
            String planId,
            Instant expiresAt,
            List<ActionRef> actions
    ) {
        if (planId == null || planId.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("计划占用必须包含 ID 和过期时间");
        }
        Set<String> keys = new HashSet<>();
        for (ActionRef action : actions) {
            if (action != null) {
                keys.add(key(action.moduleId(), action.actionId()));
            }
        }
        plans.put(planId, new PlanUsage(expiresAt, Set.copyOf(keys)));
    }

    /** 移除指定计划占用的 Action；计划不存在时保持幂等。 */
    public synchronized void removePlan(String planId) {
        if (planId != null) {
            plans.remove(planId);
        }
    }

    /**
     * 判断 Action 是否正被执行或被未过期计划引用。
     *
     * <p>查询前先清理到期计划；activeActions 中的执行占用不依赖时间。</p>
     */
    public synchronized boolean isActionInUse(
            String moduleId,
            String actionId,
            Instant now
    ) {
        String key = key(moduleId, actionId);
        pruneExpired(now);
        return activeActions.containsKey(key)
                || plans.values().stream().anyMatch(plan -> plan.actions().contains(key));
    }

    /** 返回当前执行与未过期计划占用的稳定排序 Action 键快照。 */
    public synchronized List<String> inUseActionKeys(Instant now) {
        pruneExpired(now);
        Set<String> keys = new HashSet<>(activeActions.keySet());
        plans.values().forEach(plan -> keys.addAll(plan.actions()));
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    /**
     * 为一次单 Action 调用增加引用计数，返回后必须关闭许可以释放占用。
     */
    public synchronized ActionPermit beginAction(String moduleId, String actionId) {
        String key = key(moduleId, actionId);
        retain(List.of(key));
        return new ActionPermit(List.of(key));
    }

    /**
     * 在注册表锁内把未执行计划转换为执行占用，直到返回的许可关闭。
     *
     * <p>计划不存在时拒绝交接；计划 Action 会按稳定顺序一次性增加引用计数。</p>
     */
    public synchronized ActionPermit beginPlanExecution(String planId) {
        PlanUsage plan = plans.remove(planId);
        if (plan == null) {
            throw new IllegalStateException("AI 计划没有可交接的接口占用：" + planId);
        }
        List<String> keys = plan.actions().stream().sorted().toList();
        retain(keys);
        return new ActionPermit(keys);
    }

    /**
     * 在注册表监视器内执行一个短操作。
     *
     * <p>用于把“检查是否使用中”和“写入动态 AI 开关”组合为不可交错的临界区。</p>
     */
    public synchronized <T> T withActionLock(Supplier<T> operation) {
        return operation.get();
    }

    /** 清理在给定时刻已到期的计划引用；null 时间按当前时刻处理。 */
    private void pruneExpired(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        plans.entrySet().removeIf(entry -> !effectiveNow.isBefore(entry.getValue().expiresAt()));
    }

    /** 使用 moduleId/actionId 文本构造注册表键。 */
    private static String key(String moduleId, String actionId) {
        return String.valueOf(moduleId) + "/" + String.valueOf(actionId);
    }

    /** 为每个 Action 键增加一次执行引用计数。 */
    private void retain(List<String> keys) {
        keys.forEach(key -> activeActions.merge(key, 1, Integer::sum));
    }

    /** 未执行计划和执行占用使用的 Action 引用，构造时要求模块与动作标识非空。 */
    public record ActionRef(String moduleId, String actionId) {
        public ActionRef {
            if (moduleId == null || moduleId.isBlank()
                    || actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("Action 占用必须包含 moduleId 和 actionId");
            }
        }
    }

    /** 内存中的计划占用，包含到期时刻和去重后的 Action 键集合。 */
    private record PlanUsage(Instant expiresAt, Set<String> actions) {
    }

    /**
     * Action 使用许可。
     *
     * <p>关闭许可会减少对应引用计数；重复关闭保持幂等，不重复释放。</p>
     */
    public final class ActionPermit implements AutoCloseable {

        private final List<String> keys;
        private boolean closed;

        private ActionPermit(List<String> keys) {
            this.keys = List.copyOf(keys);
        }

        /** 释放本许可持有的全部 Action 占用。 */
        @Override
        public void close() {
            synchronized (InterfaceUsageRegistry.this) {
                if (closed) {
                    return;
                }
                closed = true;
                for (String key : keys) {
                    Integer count = activeActions.get(key);
                    if (count == null || count <= 1) {
                        activeActions.remove(key);
                    } else {
                        activeActions.put(key, count - 1);
                    }
                }
            }
        }
    }
}
