package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code module_system_control} 的宿主治理动作实现。
 *
 * <p>该类只实现白名单中的两个 WRITE Action，并把操作委托给 {@link ModuleControlService}。
 * 快照保存操作前状态，回滚只在当前状态仍等于本次执行结果时恢复，避免覆盖后续用户变更。</p>
 */
@Service
public class ModuleControlActionService {

    private final ModuleControlService moduleControl;

    public ModuleControlActionService(ModuleControlService moduleControl) {
        this.moduleControl = moduleControl;
    }

    /** 判断指定系统模块动作是否由本服务处理。 */
    public boolean supports(String moduleId, String actionId) {
        return ModuleControlInterfaces.MODULE_ID.equals(moduleId)
                && ModuleControlInterfaces.isAction(actionId);
    }

    /** 采集系统治理动作执行前的可序列化状态。 */
    public Map<String, Object> prepareSnapshot(ModuleInvocation invocation) {
        return switch (invocation.actionId()) {
            case ModuleControlInterfaces.ACTION_SET_MODULE_ENABLED -> {
                ModuleControlService.ActivationSnapshot snapshot =
                        moduleControl.activationSnapshot(requireText(invocation, "moduleId"));
                yield Map.of(
                        "moduleId", snapshot.moduleId(),
                        "enabled", snapshot.enabled(),
                        "revision", snapshot.revision()
                );
            }
            case ModuleControlInterfaces.ACTION_SET_INTERFACE_CALLABLE -> {
                String moduleId = requireText(invocation, "moduleId");
                String kind = requireText(invocation, "kind");
                String interfaceId = requireText(invocation, "interfaceId");
                ModuleControlService.InterfaceCallableSnapshot snapshot =
                        moduleControl.interfaceCallableSnapshot(moduleId, kind, interfaceId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("moduleId", snapshot.moduleId());
                result.put("kind", snapshot.kind());
                result.put("interfaceId", snapshot.interfaceId());
                result.put("enabled", snapshot.enabled());
                result.put("revision", snapshot.revision());
                yield Map.copyOf(result);
            }
            default -> throw unsupported(invocation.actionId());
        };
    }

    /** 执行一个白名单系统治理动作并返回结构化输出。 */
    public ModuleResult execute(ModuleInvocation invocation) {
        return switch (invocation.actionId()) {
            case ModuleControlInterfaces.ACTION_SET_MODULE_ENABLED -> {
                String moduleId = requireText(invocation, "moduleId");
                boolean enabled = requireBoolean(invocation, "enabled");
                ModuleControlService.ActivationChange change =
                        moduleControl.setActivationForControl(moduleId, enabled);
                yield new ModuleResult(
                        change.changed() ? "模块启停状态已更新" : "模块启停状态未变化",
                        activationOutput(change)
                );
            }
            case ModuleControlInterfaces.ACTION_SET_INTERFACE_CALLABLE -> {
                String moduleId = requireText(invocation, "moduleId");
                String kind = requireText(invocation, "kind");
                String interfaceId = requireText(invocation, "interfaceId");
                boolean enabled = requireBoolean(invocation, "enabled");
                ModuleControlService.InterfaceCallableChange change =
                        moduleControl.setInterfaceCallableForControl(
                                moduleId,
                                kind,
                                interfaceId,
                                enabled
                        );
                yield new ModuleResult(
                        change.changed() ? "接口 AI 可调用状态已更新" : "接口 AI 可调用状态未变化",
                        interfaceOutput(change)
                );
            }
            default -> throw unsupported(invocation.actionId());
        };
    }

    /** 根据执行前快照恢复系统治理状态。 */
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> output
    ) {
        return switch (invocation.actionId()) {
            case ModuleControlInterfaces.ACTION_SET_MODULE_ENABLED -> {
                String moduleId = requiredText(snapshot, "moduleId");
                boolean previousEnabled = requiredBoolean(snapshot, "enabled");
                boolean targetEnabled = requiredBoolean(output, "enabled");
                ModuleControlService.ActivationSnapshot current =
                        moduleControl.activationSnapshot(moduleId);
                if (current.enabled() == previousEnabled) {
                    yield ModuleResult.completed("模块启停状态已处于回滚目标");
                }
                if (current.enabled() != targetEnabled) {
                    throw rollbackConflict("模块启停状态已被后续操作修改");
                }
                ModuleControlService.ActivationChange restored =
                        moduleControl.setActivationForControl(moduleId, previousEnabled);
                yield new ModuleResult("模块启停状态已回滚", activationOutput(restored));
            }
            case ModuleControlInterfaces.ACTION_SET_INTERFACE_CALLABLE -> {
                String moduleId = requiredText(snapshot, "moduleId");
                String kind = requiredText(snapshot, "kind");
                String interfaceId = requiredText(snapshot, "interfaceId");
                boolean previousEnabled = requiredBoolean(snapshot, "enabled");
                boolean targetEnabled = requiredBoolean(output, "enabled");
                ModuleControlService.InterfaceCallableSnapshot current =
                        moduleControl.interfaceCallableSnapshot(moduleId, kind, interfaceId);
                if (current.enabled() == previousEnabled) {
                    yield ModuleResult.completed("接口 AI 可调用状态已处于回滚目标");
                }
                if (current.enabled() != targetEnabled) {
                    throw rollbackConflict("接口 AI 可调用状态已被后续操作修改");
                }
                ModuleControlService.InterfaceCallableChange restored =
                        moduleControl.setInterfaceCallableForControl(
                                moduleId,
                                kind,
                                interfaceId,
                                previousEnabled
                        );
                yield new ModuleResult(
                        "接口 AI 可调用状态已回滚",
                        interfaceOutput(restored)
                );
            }
            default -> throw unsupported(invocation.actionId());
        };
    }

    /** 构造模块启停动作输出，并保留操作前状态。 */
    private static Map<String, Object> activationOutput(
            ModuleControlService.ActivationChange change
    ) {
        return Map.of(
                "moduleId", change.moduleId(),
                "previousEnabled", change.previousEnabled(),
                "enabled", change.enabled(),
                "changed", change.changed(),
                "revision", change.revision()
        );
    }

    /** 构造接口开关动作输出，并保留操作前状态。 */
    private static Map<String, Object> interfaceOutput(
            ModuleControlService.InterfaceCallableChange change
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("moduleId", change.moduleId());
        output.put("kind", change.kind());
        output.put("interfaceId", change.interfaceId());
        output.put("previousEnabled", change.previousEnabled());
        output.put("enabled", change.enabled());
        output.put("changed", change.changed());
        output.put("revision", change.revision());
        return Map.copyOf(output);
    }

    private static String requireText(ModuleInvocation invocation, String name) {
        Object value = invocation.arguments().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidArguments(name + " 必须是非空文本");
        }
        return text.trim();
    }

    private static boolean requireBoolean(ModuleInvocation invocation, String name) {
        Object value = invocation.arguments().get(name);
        if (!(value instanceof Boolean flag)) {
            throw invalidArguments(name + " 必须是布尔值");
        }
        return flag;
    }

    private static String requiredText(Map<String, Object> source, String name) {
        Object value = source.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("系统治理快照缺少文本字段：" + name);
        }
        return text;
    }

    private static boolean requiredBoolean(Map<String, Object> source, String name) {
        Object value = source.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException("系统治理状态缺少布尔字段：" + name);
        }
        return flag;
    }

    private static ApiException invalidArguments(String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_ACTION_ARGUMENTS",
                message
        );
    }

    private static ApiException unsupported(String actionId) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "MODULE_PROTECTED",
                "未授权的系统治理动作：" + actionId
        );
    }

    private static ApiException rollbackConflict(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "MODULE_CONTROL_ROLLBACK_CONFLICT",
                message
        );
    }
}
