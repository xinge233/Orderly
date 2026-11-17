package com.orderly.host.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.OutputSchemaValidator;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 计划和 AI 直执共同使用的动作授权、校验、快照、审计与回滚核心。 */
@Service
public class ActionExecutionCore {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final ContractCatalog catalog;
    private final WorkspaceRuntime runtime;
    private final ExecutionJournal journal;
    private final ModuleControlService moduleControl;
    private final ObjectMapper objectMapper;

    public ActionExecutionCore(
            ContractCatalog catalog,
            WorkspaceRuntime runtime,
            ExecutionJournal journal,
            ModuleControlService moduleControl,
            ObjectMapper objectMapper
    ) {
        this.catalog = catalog;
        this.runtime = runtime;
        this.journal = journal;
        this.moduleControl = moduleControl;
        this.objectMapper = objectMapper;
    }

    public ResolvedAction requireAiTarget(String moduleId, String actionId) {
        ResolvedAction target = requireEnabledTarget(moduleId, actionId);
        if (!target.action().aiCallable()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ACTION_NOT_AI_CALLABLE",
                    "动作禁止 AI 调用：" + actionId
            );
        }
        return target;
    }

    public ResolvedAction requirePlanTarget(String moduleId, String actionId) {
        return requireEnabledTarget(moduleId, actionId);
    }

    public void validateArguments(ActionContract action, Map<String, Object> arguments) {
        ActionArguments.validate(action, arguments);
    }

    public ActionResult execute(
            String executionId,
            String stepId,
            String moduleId,
            String actionId,
            Map<String, Object> arguments,
            String origin
    ) {
        ResolvedAction target = isAiOrigin(origin)
                ? requireAiTarget(moduleId, actionId)
                : requireEnabledTarget(moduleId, actionId);
        validateArguments(target.action(), arguments);
        ModuleInvocation invocation = new ModuleInvocation(
                moduleId,
                actionId,
                executionId,
                stepId,
                arguments
        );

        appendStep(
                origin,
                executionId,
                stepId,
                moduleId,
                actionId,
                "RUNNING",
                target.action().title()
        );
        Map<String, Object> snapshot;
        try {
            snapshot = normalizeObject(target.module().prepareSnapshot(invocation, runtime), "快照");
            if (snapshot == null) {
                throw new IllegalStateException("模块未提供快照");
            }
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            appendStep(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "FAILED",
                    message
            );
            throw new ActionExecutionException(message, exception, Map.of(), Map.of(), false);
        }

        ModuleResult result;
        try {
            result = target.module().execute(invocation, runtime);
            if (result == null) {
                throw new IllegalStateException("模块返回空结果");
            }
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            appendStep(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "FAILED",
                    message
            );
            throw new ActionExecutionException(message, exception, snapshot, Map.of(), false);
        }

        try {
            Map<String, Object> output = normalizeObject(result.output(), "输出");
            OutputSchemaValidator.validateOutput(
                    target.action().outputSchema(),
                    output,
                    objectMapper,
                    actionId
            );
            appendStep(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "COMPLETED",
                    result.message()
            );
            return new ActionResult(snapshot, output, result.message());
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            appendStep(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "FAILED",
                    message
            );
            throw new ActionExecutionException(
                    message,
                    exception,
                    snapshot,
                    normalizeObject(result.output(), "输出"),
                    true
            );
        }
    }

    public String rollback(
            String executionId,
            String stepId,
            String moduleId,
            String actionId,
            Map<String, Object> arguments,
            Map<String, Object> snapshot,
            Map<String, Object> output,
            String origin
    ) {
        ResolvedAction target = requireOwnedTarget(moduleId, actionId);
        ModuleInvocation invocation = new ModuleInvocation(
                moduleId,
                actionId,
                executionId,
                stepId,
                arguments
        );
        try {
            ModuleResult result = target.module().rollback(
                    invocation,
                    snapshot == null ? Map.of() : snapshot,
                    output == null ? Map.of() : output,
                    runtime
            );
            if (result == null) {
                throw new IllegalStateException("模块返回空回滚结果");
            }
            appendRollback(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "ROLLED_BACK",
                    result.message()
            );
            return result.message();
        } catch (RuntimeException exception) {
            appendRollback(
                    origin,
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    "FAILED",
                    safeMessage(exception)
            );
            throw exception;
        }
    }

    private ResolvedAction requireEnabledTarget(String moduleId, String actionId) {
        ResolvedAction target = requireOwnedTarget(moduleId, actionId);
        if (target.moduleContract().system() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_PROTECTED",
                    "系统模块不通过动作执行入口调用：" + moduleId
            );
        }
        if (!moduleControl.isEnabled(moduleId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_DISABLED",
                    "模块已停用：" + moduleId
            );
        }
        return target;
    }

    private ResolvedAction requireOwnedTarget(String moduleId, String actionId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "MODULE_NOT_FOUND",
                "模块不存在：" + moduleId
        ));
        ActionContract action = module.actions().stream()
                .filter(candidate -> candidate.id().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ACTION_NOT_FOUND",
                        "动作不存在或不属于模块：" + moduleId + "/" + actionId
                ));
        if (!catalog.moduleIdForAction(actionId).equals(moduleId)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ACTION_OWNERSHIP_MISMATCH",
                    "动作归属校验失败：" + moduleId + "/" + actionId
            );
        }
        return new ResolvedAction(
                module,
                action,
                catalog.requireExecutor(moduleId)
        );
    }

    private void appendStep(
            String origin,
            String executionId,
            String stepId,
            String moduleId,
            String actionId,
            String status,
            String message
    ) {
        journal.append(
                "STEP",
                origin,
                executionId,
                stepId,
                moduleId,
                actionId,
                status,
                message
        );
    }

    private void appendRollback(
            String origin,
            String executionId,
            String stepId,
            String moduleId,
            String actionId,
            String status,
            String message
    ) {
        journal.append(
                "ROLLBACK_STEP",
                origin,
                executionId,
                stepId,
                moduleId,
                actionId,
                status,
                message
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }

    private boolean isAiOrigin(String origin) {
        return "AI_DIRECT".equals(origin) || "AI_PLAN".equals(origin);
    }

    private Map<String, Object> normalizeObject(Map<String, Object> value, String label) {
        if (value == null) {
            throw new IllegalStateException("模块返回空" + label);
        }
        try {
            return objectMapper.convertValue(value, JSON_OBJECT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("模块" + label + "无法序列化为 JSON 对象", exception);
        }
    }

    public record ResolvedAction(
            ModuleContract moduleContract,
            ActionContract action,
            OrderlyModule module
    ) {
        public RiskLevel risk() {
            return action.risk();
        }
    }

    public record ActionResult(
            Map<String, Object> snapshot,
            Map<String, Object> output,
            String message
    ) {
    }
}
