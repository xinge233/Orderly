package com.orderly.host.plan;

import com.orderly.kernel.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 计划中的单步状态与审计数据。 */
public final class ExecutionStep {

    private final String id;
    private final String moduleId;
    private final String actionId;
    private final String title;
    private final String description;
    private final Map<String, Object> arguments;
    private final RiskLevel risk;
    private final String confirmationPhrase;
    private final List<String> dependsOn;
    private StepStatus status = StepStatus.WAITING;
    private Map<String, Object> snapshot = Map.of();
    private Map<String, Object> output = Map.of();
    private String message = "";

    public ExecutionStep(
            String id,
            String moduleId,
            String actionId,
            String title,
            String description,
            Map<String, Object> arguments,
            RiskLevel risk,
            String confirmationPhrase,
            List<String> dependsOn
    ) {
        this.id = id;
        this.moduleId = moduleId;
        this.actionId = actionId;
        this.title = title;
        this.description = description;
        this.arguments = Map.copyOf(new LinkedHashMap<>(arguments));
        this.risk = risk;
        this.confirmationPhrase = confirmationPhrase;
        this.dependsOn = List.copyOf(dependsOn);
    }

    public String id() {
        return id;
    }

    public String moduleId() {
        return moduleId;
    }

    public String actionId() {
        return actionId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    public RiskLevel risk() {
        return risk;
    }

    public String confirmationPhrase() {
        return confirmationPhrase;
    }

    public List<String> dependsOn() {
        return dependsOn;
    }

    public StepStatus status() {
        return status;
    }

    public void status(StepStatus value) {
        this.status = value;
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    public void snapshot(Map<String, Object> value) {
        this.snapshot = Map.copyOf(value);
    }

    public Map<String, Object> output() {
        return output;
    }

    public void output(Map<String, Object> value) {
        this.output = Map.copyOf(value);
    }

    public String message() {
        return message;
    }

    public void message(String value) {
        this.message = value == null ? "" : value;
    }
}
