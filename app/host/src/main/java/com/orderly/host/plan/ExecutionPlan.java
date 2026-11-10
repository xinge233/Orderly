package com.orderly.host.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 服务端保存的计划状态。 */
public final class ExecutionPlan {

    private final String id;
    private final String goal;
    private final String kind;
    private final String message;
    private final List<String> assumptions;
    private final String impact;
    private final double confidence;
    private final List<ExecutionStep> steps;
    private final Instant createdAt;
    private PlanStatus status;
    private Instant completedAt;

    public ExecutionPlan(
            String id,
            String goal,
            String kind,
            String message,
            List<String> assumptions,
            String impact,
            double confidence,
            List<ExecutionStep> steps,
            Instant createdAt
    ) {
        this.id = id;
        this.goal = goal;
        this.kind = kind;
        this.message = message;
        this.assumptions = List.copyOf(assumptions);
        this.impact = impact;
        this.confidence = confidence;
        this.steps = new ArrayList<>(steps);
        this.createdAt = createdAt;
        this.status = PlanStatus.PREVIEW;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public String kind() {
        return kind;
    }

    public String message() {
        return message;
    }

    public List<String> assumptions() {
        return assumptions;
    }

    public String impact() {
        return impact;
    }

    public double confidence() {
        return confidence;
    }

    public List<ExecutionStep> steps() {
        return steps;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PlanStatus status() {
        return status;
    }

    public void status(PlanStatus value) {
        this.status = value;
        if (value == PlanStatus.COMPLETED
                || value == PlanStatus.REJECTED
                || value == PlanStatus.ROLLED_BACK
                || value == PlanStatus.PARTIALLY_ROLLED_BACK) {
            this.completedAt = Instant.now();
        }
    }

    public Instant completedAt() {
        return completedAt;
    }
}
