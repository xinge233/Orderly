package com.orderly.host.planner;

import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.PlannerRuleContract;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.kernel.RiskLevel;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线本地规则计划器。
 *
 * <p>它不是外部大模型，也不连接任何模型服务。可用句式全部来自已安装模块契约中的
 * 本地正则规则，框架不保存具体业务动作或模块名称。计划器只产生结构化步骤和澄清
 * 结果，不持有工作区写权限、不执行 Action、不写审计，也无法泛化到规则未覆盖的
 * 自然语言。</p>
 */
@Component
public class LocalRulePlanner {

    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final ModuleControlCoordinator coordinator;

    /** 注入契约索引与模块治理依赖；构造过程不扫描文件或不生成计划。 */
    public LocalRulePlanner(
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            ModuleControlCoordinator coordinator
    ) {
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.coordinator = coordinator;
    }

    /**
     * 在稳定的模块治理读取视图下生成规则计划。
     *
     * <p>调用期间与模块启停/刷新提交互斥，确保规则所属模块不会在读取过程中改变。
     * 方法无业务副作用，不调用外部模型；无法可靠映射时返回 {@code QUESTION}，
     * 而不是猜测 Action。</p>
     */
    public PlannerResult draft(String goal) {
        return coordinator.read(() -> draftEnabled(goal));
    }

    /**
     * 对已启用业务模块声明的规则执行整句或多步匹配。
     *
     * <p>先尝试整句规则，再按明确分隔符拆成顺序步骤。每段必须完整匹配至少一条契约
     * 规则，否则返回澄清结果。参数可能被清理为工作区相对路径；任何捕获值或路径非法
     * 都会抛出异常，不生成部分可执行计划。</p>
     */
    private PlannerResult draftEnabled(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isBlank()) {
            return question("请描述要完成的受控操作。", "未产生任何变更。");
        }
        List<PlannerRuleContract> rules = catalog.plannerRules().stream()
                .filter(rule -> {
                    String moduleId = catalog.moduleIdForAction(rule.actionId());
                    return !catalog.isSystemModule(moduleId) && moduleControl.isEnabled(moduleId);
                })
                .toList();
        if (rules.isEmpty()) {
            return question(
                    "当前没有已安装模块提供本地计划规则。请安装对应模块后重试。",
                    "未生成可执行计划。"
            );
        }

        PlannerStep wholeGoal = match(normalized, rules, true);
        if (wholeGoal != null) {
            return plan(List.of(wholeGoal));
        }

        String[] rawParts = normalized.split(
                "(?:\\s*[，,]\\s*然后\\s*|\\s+然后\\s*|\\s*[；;]\\s*|\\s*\\n+\\s*)"
        );
        List<PlannerStep> steps = new ArrayList<>();
        for (String rawPart : rawParts) {
            String part = rawPart.trim().replaceFirst("[。！!]+$", "").trim();
            if (part.isBlank()) {
                continue;
            }
            PlannerStep parsed = match(part, rules, false);
            if (parsed == null) {
                return question(
                        "当前已安装模块没有声明可识别“" + part + "”的规则。请查看模块提供的示例指令。",
                        "未产生任何变更。"
                );
            }
            steps.add(parsed);
        }
        if (steps.isEmpty()) {
            return question("没有识别到可预览的操作步骤。", "未产生任何变更。");
        }
        return plan(steps);
    }

    /**
     * 汇总已匹配步骤的风险影响并生成可审查计划。
     *
     * <p>方法只读取契约风险，不执行动作。非只读动作计入写入数量，删除类动作从项目
     * 回收站机制约束中单独计数；最终文本明确说明确认前不会产生真实变更。</p>
     */
    private PlannerResult plan(List<PlannerStep> steps) {
        List<PlannerStep> immutable = List.copyOf(steps);
        long writes = 0;
        long deletes = 0;
        for (PlannerStep step : immutable) {
            ActionContract action = catalog.requireAction(step.actionId());
            if (action.risk() != RiskLevel.READ_ONLY) {
                writes++;
            }
            if (action.risk() == RiskLevel.DELETE) {
                deletes++;
            }
        }
        String impact = "共 " + immutable.size() + " 步，其中写入 " + writes + " 步"
                + (deletes > 0 ? "，删除类 " + deletes + " 步" : "")
                + "；确认前不会产生真实变更。";
        return new PlannerResult(
                "PLAN",
                "已生成 " + immutable.size() + " 步计划，请检查后确认执行。",
                defaultAssumptions(),
                impact,
                0.96,
                immutable
        );
    }

    /**
     * 用一条已登记规则匹配输入并提取 Action 参数。
     *
     * <p>{@code requireWholeGoal} 决定是否只使用整句规则。正则必须以整段输入完全
     * 匹配；捕获组必须产生非空值。路径参数会去除工作区相对路径中的常见噪声并拒绝
     * 越界，其余参数仅去除首尾空白。无匹配返回 {@code null}，捕获或路径非法时失败。</p>
     */
    private PlannerStep match(
            String input,
            List<PlannerRuleContract> rules,
            boolean requireWholeGoal
    ) {
        for (PlannerRuleContract rule : rules) {
            if (requireWholeGoal != rule.wholeGoal()) {
                continue;
            }
            Matcher matcher = Pattern.compile(rule.pattern()).matcher(input);
            if (!matcher.matches()) {
                continue;
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            rule.argumentGroups().forEach((name, group) -> {
                String value = matcher.group(group);
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("规则捕获值为空：" + rule.id() + "/" + name);
                }
                arguments.put(name, rule.pathArguments().contains(name)
                        ? cleanPath(value)
                        : value.trim());
            });
            return new PlannerStep(rule.actionId(), Map.copyOf(arguments));
        }
        return null;
    }

    /**
     * 将规则捕获值规范为工作区相对路径。
     *
     * <p>反斜杠统一为正斜杠并去除前导 {@code ./}。绝对路径、父目录段和中间
     * {@code ../} 均失败；方法只做文本规范化，不检查目标是否存在，也不解析真实路径。</p>
     */
    private String cleanPath(String value) {
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank()
                || Path.of(path).isAbsolute()
                || path.equals("..")
                || path.startsWith("../")
                || path.contains("/../")) {
            throw new IllegalArgumentException("只允许工作区内的相对路径：" + value);
        }
        return path;
    }

    /**
     * 构造不执行任何动作的澄清结果。
     *
     * <p>假设固定包含“未连接外部模型”，影响说明由调用方明确写出未产生变更或未生成
     * 可执行计划。方法不写计划表，保存工作由上层服务负责。</p>
     */
    private PlannerResult question(String message, String impact) {
        return new PlannerResult(
                "QUESTION",
                message,
                List.of("本地规则计划器未连接外部模型。"),
                impact,
                0.2,
                List.of()
        );
    }

    /** 返回成功规则计划的固定诚实性假设；每次调用创建不可变字符串列表。 */
    private List<String> defaultAssumptions() {
        return List.of(
                "所有操作仅使用已安装模块在契约中声明的动作。",
                "目标冲突时不覆盖已有对象；删除只进入项目回收站。",
                "执行前必须显式确认，具体风险以服务端契约为准。"
        );
    }
}
