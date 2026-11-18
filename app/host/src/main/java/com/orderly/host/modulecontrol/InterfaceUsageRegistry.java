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
 * <p>该注册表只保存短时运行态，不持久化；旧版计划不参与占用判断。</p>
 */
@Component
public class InterfaceUsageRegistry {

    private final Map<String, PlanUsage> plans = new HashMap<>();
    private final Map<String, Integer> activeActions = new HashMap<>();

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

    public synchronized void removePlan(String planId) {
        if (planId != null) {
            plans.remove(planId);
        }
    }

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

    public synchronized List<String> inUseActionKeys(Instant now) {
        pruneExpired(now);
        Set<String> keys = new HashSet<>(activeActions.keySet());
        plans.values().forEach(plan -> keys.addAll(plan.actions()));
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    public synchronized ActionPermit beginAction(String moduleId, String actionId) {
        String key = key(moduleId, actionId);
        retain(List.of(key));
        return new ActionPermit(List.of(key));
    }

    /**
     * 原子地把未执行计划转换为执行占用，直到整个计划执行结束。
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

    public synchronized <T> T withActionLock(Supplier<T> operation) {
        return operation.get();
    }

    private void pruneExpired(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        plans.entrySet().removeIf(entry -> !effectiveNow.isBefore(entry.getValue().expiresAt()));
    }

    private static String key(String moduleId, String actionId) {
        return String.valueOf(moduleId) + "/" + String.valueOf(actionId);
    }

    private void retain(List<String> keys) {
        keys.forEach(key -> activeActions.merge(key, 1, Integer::sum));
    }

    public record ActionRef(String moduleId, String actionId) {
        public ActionRef {
            if (moduleId == null || moduleId.isBlank()
                    || actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("Action 占用必须包含 moduleId 和 actionId");
            }
        }
    }

    private record PlanUsage(Instant expiresAt, Set<String> actions) {
    }

    public final class ActionPermit implements AutoCloseable {

        private final List<String> keys;
        private boolean closed;

        private ActionPermit(List<String> keys) {
            this.keys = List.copyOf(keys);
        }

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
