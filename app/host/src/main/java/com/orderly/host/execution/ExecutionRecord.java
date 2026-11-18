package com.orderly.host.execution;

import com.orderly.host.event.ExecutionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可跨重启查询和回滚的完整执行记录。
 *
 * <p>记录保存有序步骤、聚合后的参数/输出/快照和审计事件。聚合映射以 stepId 为键；
 * 记录本身不可变，状态推进通过 {@link #withChanges} 创建新快照。</p>
 */
public record ExecutionRecord(
        String executionId,
        String origin,
        String status,
        List<ExecutionRecordStep> steps,
        Map<String, Object> arguments,
        Map<String, Object> outputs,
        Map<String, Object> snapshots,
        List<ExecutionEvent> events,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
    public ExecutionRecord {
        steps = List.copyOf(steps == null ? List.of() : steps);
        arguments = copy(arguments);
        outputs = copy(outputs);
        snapshots = copy(snapshots);
        events = List.copyOf(events == null ? List.of() : events);
    }

    /**
     * 创建状态推进后的新记录。
     *
     * <p>根据新步骤重新聚合参数、输出和快照，将新事件按序号与既有事件合并，并刷新
     * updatedAt；createdAt 保持不变。该操作不写磁盘，持久化由调用方负责。</p>
     */
    public ExecutionRecord withChanges(
            String nextStatus,
            List<ExecutionRecordStep> nextSteps,
            List<ExecutionEvent> nextEvents,
            Instant nextStartedAt,
            Instant nextCompletedAt
    ) {
        List<ExecutionRecordStep> stableSteps = List.copyOf(nextSteps);
        return new ExecutionRecord(
                executionId,
                origin,
                nextStatus,
                stableSteps,
                aggregate(stableSteps, StepPart.ARGUMENTS),
                aggregate(stableSteps, StepPart.OUTPUT),
                aggregate(stableSteps, StepPart.SNAPSHOT),
                mergeEvents(events, nextEvents),
                createdAt,
                nextStartedAt,
                nextCompletedAt,
                Instant.now()
        );
    }

    /**
     * 按步骤顺序抽取某一类数据并按 stepId 聚合。
     *
     * <p>返回不可变映射，但不承诺序列化后的键顺序；后出现的同名 stepId 会覆盖前值，
     * 正常执行记录不应出现重复步骤 ID。</p>
     */
    public static Map<String, Object> aggregate(
            List<ExecutionRecordStep> steps,
            StepPart part
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ExecutionRecordStep step : steps) {
            result.put(step.stepId(), switch (part) {
                case ARGUMENTS -> step.arguments();
                case OUTPUT -> step.output();
                case SNAPSHOT -> step.snapshot();
            });
        }
        return Map.copyOf(result);
    }

    /** 按事件序号合并既有事件与新增事件，序号相同的供应值覆盖既有值。 */
    public static List<ExecutionEvent> mergeEvents(
            List<ExecutionEvent> existing,
            List<ExecutionEvent> supplied
    ) {
        Map<Long, ExecutionEvent> merged = new LinkedHashMap<>();
        for (ExecutionEvent event : existing) {
            merged.put(event.seq(), event);
        }
        for (ExecutionEvent event : supplied) {
            merged.put(event.seq(), event);
        }
        return new ArrayList<>(merged.values());
    }

    /** 复制顶层映射为不可变映射；嵌套值仍由调用方按 JSON 数据类型使用。 */
    private static Map<String, Object> copy(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                value == null ? Map.of() : value
        ));
    }

    /** 执行记录聚合时可选的数据部分。 */
    public enum StepPart {
        ARGUMENTS,
        OUTPUT,
        SNAPSHOT
    }
}
