package com.orderly.host.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.kernel.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 全局 AI 接入、会话和独立计划接口使用的稳定数据结构。 */
public final class AiContracts {

    private AiContracts() {
    }

    public enum Mode {
        DEEPSEEK,
        CUSTOM
    }

    public record SettingsRequest(
            Mode mode,
            String apiKey,
            String baseUrl,
            String model,
            Boolean allowDeleteDirect
    ) {
        @Override
        public String toString() {
            return "SettingsRequest["
                    + "mode=" + mode
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? null : "[redacted]")
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", allowDeleteDirect=" + allowDeleteDirect
                    + "]";
        }
    }

    public record SettingsView(
            Mode mode,
            String baseUrl,
            String model,
            boolean configured,
            boolean allowDeleteDirect,
            boolean allowLocalProvider
    ) {
    }

    public record ConnectionTest(boolean ok, String message) {
    }

    public record TurnRequest(
            String goal,
            String conversationId,
            String clientRequestId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnResponse(
            String kind,
            String requestId,
            String conversationId,
            String message,
            RiskLevel highestRisk,
            List<Action> actions,
            Plan plan,
            Question question,
            ExecutionRecord execution
    ) {
    }

    public record Action(
            String moduleId,
            String actionId,
            Map<String, Object> arguments
    ) {
        public Action {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public record Question(
            String prompt,
            List<String> options
    ) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Plan(
            String planId,
            String goal,
            String status,
            RiskLevel highestRisk,
            List<PlanStep> steps,
            Instant createdAt,
            Instant expiresAt
    ) {
        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record PlanStep(
            String id,
            String moduleId,
            String actionId,
            String title,
            String description,
            RiskLevel risk,
            Map<String, Object> arguments
    ) {
        public PlanStep {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 模型返回的内部结构；对外不会暴露原始模型文本。 */
    public record ModelDecision(
            String kind,
            String message,
            List<Action> actions,
            Question question
    ) {
        public ModelDecision {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
