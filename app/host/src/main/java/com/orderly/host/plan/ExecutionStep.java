package com.orderly.host.plan;

import com.orderly.kernel.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧版计划中单个步骤的可变执行状态与审计数据。
 *
 * <p>步骤初始为 {@link StepStatus#WAITING}，由执行服务串行推进。快照、输出和消息会
 * 随执行或回滚更新，但不在此类中持久化；执行服务在每次状态变化后把副本写入执行
 * 记录。实例不是线程安全对象，依赖宿主协调器串行访问。</p>
 */
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

    /**
     * 创建等待执行的步骤定义。
     *
     * <p>参数与依赖列表复制为顶层不可变值，但嵌套 JSON 参数不会递归冻结。构造不会
     * 读取工作区、调用模块或校验依赖 DAG；这些检查由计划服务在保存前完成。</p>
     */
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

    /** 替换内存中的步骤状态；只供计划执行服务按执行顺序调用，不触发持久化。 */
    public void status(StepStatus value) {
        this.status = value;
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    /** 保存可 JSON 化的逆操作快照；复制顶层映射，不校验内容完整性。 */
    public void snapshot(Map<String, Object> value) {
        this.snapshot = Map.copyOf(value);
    }

    public Map<String, Object> output() {
        return output;
    }

    /** 保存模块返回的结构化输出；复制顶层映射，供持久化和后续 Action 引用。 */
    public void output(Map<String, Object> value) {
        this.output = Map.copyOf(value);
    }

    public String message() {
        return message;
    }

    /** 更新面向审计和前端的结果摘要；{@code null} 归一为空字符串。 */
    public void message(String value) {
        this.message = value == null ? "" : value;
    }
}
