package com.orderly.host.execution;

import com.orderly.host.event.ExecutionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 可跨重启回滚的完整执行记录。 */
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

    private static Map<String, Object> copy(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                value == null ? Map.of() : value
        ));
    }

    public enum StepPart {
        ARGUMENTS,
        OUTPUT,
        SNAPSHOT
    }
}
