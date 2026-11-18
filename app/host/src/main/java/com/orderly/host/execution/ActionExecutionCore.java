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
import com.orderly.host.modulecontrol.InterfaceUsageRegistry;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.RiskLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 计划和 AI 直执共同使用的 Action 担保核心。
 *
 * <p>该类在执行前解析模块归属、业务模块启用状态、AI 可调用开关和参数契约，
 * 正向执行前取得 JSON 快照，并在每一步写入审计事件。正向动作发生异常时不自动
 * 调用回滚；调用方需根据异常携带的 {@code executed} 标记与快照组织逆序回滚。
 * 回滚入口不检查业务模块当前是否启用，以便停用后仍能恢复既有变更。</p>
 */
@Service
public class ActionExecutionCore {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final ContractCatalog catalog;
    private final WorkspaceRuntime runtime;
    private final ExecutionJournal journal;
    private final ModuleControlService moduleControl;
    private final InterfaceUsageRegistry usage;
    private final ObjectMapper objectMapper;

    public ActionExecutionCore(
            ContractCatalog catalog,
            WorkspaceRuntime runtime,
            ExecutionJournal journal,
            ModuleControlService moduleControl,
            InterfaceUsageRegistry usage,
            ObjectMapper objectMapper
    ) {
        this.catalog = catalog;
        this.runtime = runtime;
        this.journal = journal;
        this.moduleControl = moduleControl;
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析仅供 AI 调用的动作目标。
     *
     * <p>除业务模块启用、动作归属和系统模块保护校验外，还要求用户动态开关未关闭
     * 该动作。该检查无业务副作用。</p>
     */
    public ResolvedAction requireAiTarget(String moduleId, String actionId) {
        ResolvedAction target = requireEnabledTarget(moduleId, actionId);
        if (!moduleControl.isActionAiCallableEnabled(moduleId, actionId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ACTION_AI_DISABLED",
                    "动作已被用户关闭 AI 调用：" + actionId
            );
        }
        return target;
    }

    /**
     * 解析旧版计划可执行的动作目标。
     *
     * <p>校验业务模块启用、动作归属和系统模块保护，但不应用 AI 动态开关；
     * 授权与确认仍由计划执行入口负责。</p>
     */
    public ResolvedAction requirePlanTarget(String moduleId, String actionId) {
        return requireEnabledTarget(moduleId, actionId);
    }

    /** 将参数校验委托给契约白名单校验器。 */
    public void validateArguments(ActionContract action, Map<String, Object> arguments) {
        ActionArguments.validate(action, arguments);
    }

    /**
     * 执行单个 Action，并在整个调用期间保留“使用中”占用以阻止 AI 开关被关闭。
     *
     * <p>执行顺序固定为：目标与参数校验、RUNNING 审计、快照、正向调用、输出 JSON 化
     * 与 schema 校验、COMPLETED 审计。快照或模块调用失败时不标记为已执行；正向调用
     * 后的输出处理失败若成功构造 {@link ActionExecutionException}，则标记
     * {@code executed=true}，供上层回滚已经发生的正向变更。</p>
     */
    public ActionResult execute(
            String executionId,
            String stepId,
            String moduleId,
            String actionId,
            Map<String, Object> arguments,
            String origin
    ) {
        try (var ignored = usage.beginAction(moduleId, actionId)) {
            return executeInternal(executionId, stepId, moduleId, actionId, arguments, origin);
        }
    }

    /**
     * 在已持有使用中占用的前提下执行单个 Action。
     *
     * <p>状态迁移为 RUNNING 到 COMPLETED 或 FAILED。任何异常均转换为
     * {@link ActionExecutionException}；只有正向调用已成功但后续输出处理失败时，
     * 异常的 {@code executed} 才为 true。</p>
     */
    private ActionResult executeInternal(
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

    /**
     * 使用持久化快照与输出回滚单个 Action。
     *
     * <p>回滚只要求动作归属存在，不要求模块当前启用，也不应用 AI 可调用开关。
     * 空快照或空输出会转换为空对象传给模块；成功和失败分别记录
     * ROLLED_BACK/FAILED 审计事件，异常本身继续抛给调用方以保留部分回滚状态。
     * 核心不会对重复回滚去重，模块必须依据快照和输出实现幂等逆操作。</p>
     */
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
        try (var ignored = usage.beginAction(moduleId, actionId)) {
            return rollbackInternal(
                    executionId,
                    stepId,
                    moduleId,
                    actionId,
                    arguments,
                    snapshot,
                    output,
                    origin
            );
        }
    }

    /** 执行回滚并记录 ROLLBACK_STEP 事件，不吞掉模块逆操作异常。 */
    private String rollbackInternal(
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

    /** 解析契约与执行器，并额外要求目标属于已启用的非系统业务模块。 */
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

    /**
     * 仅校验模块存在、动作存在于该模块且全局动作归属一致，并绑定执行器。
     */
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

    /** 追加单步正向执行审计事件。 */
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

    /** 追加单步回滚审计事件。 */
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

    /** 将任意运行时异常转换为可审计的非空摘要。 */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }

    /** AI 计划和 AI 直执都沿用宿主 AI 动作开关；旧版计划与模块控制不经过该判断。 */
    private boolean isAiOrigin(String origin) {
        return "AI_DIRECT".equals(origin) || "AI_PLAN".equals(origin);
    }

    /**
     * 通过 Jackson 将快照或输出转换为 JSON 对象形态，拒绝无法序列化的值。
     *
     * <p>转换结果用于持久化前校验，不替代模块自定义快照内容；null 视为模块违约。</p>
     */
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

    /** 已解析的动作目标，绑定契约、动作元数据和对应模块执行器。 */
    public record ResolvedAction(
            ModuleContract moduleContract,
            ActionContract action,
            OrderlyModule module
    ) {
        /** 返回契约声明的风险等级，作为确认策略的唯一输入。 */
        public RiskLevel risk() {
            return action.risk();
        }
    }

    /** 成功正向执行的不可变结果，包含 JSON 快照、JSON 输出和审计摘要。 */
    public record ActionResult(
            Map<String, Object> snapshot,
            Map<String, Object> output,
            String message
    ) {
    }
}
