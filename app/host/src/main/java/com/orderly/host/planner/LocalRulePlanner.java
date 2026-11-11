package com.orderly.host.planner;

import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.PlannerRuleContract;
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
 * <p>它不是外部大模型。可用句式全部来自已安装模块的契约，框架不保存任何
 * 具体业务动作或模块名称。</p>
 */
@Component
public class LocalRulePlanner {

    private final ContractCatalog catalog;

    public LocalRulePlanner(ContractCatalog catalog) {
        this.catalog = catalog;
    }

    public PlannerResult draft(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isBlank()) {
            return question("请描述要完成的受控操作。", "未产生任何变更。");
        }
        List<PlannerRuleContract> rules = catalog.plannerRules();
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

    private List<String> defaultAssumptions() {
        return List.of(
                "所有操作仅使用已安装模块在契约中声明的动作。",
                "目标冲突时不覆盖已有对象；删除只进入项目回收站。",
                "执行前必须显式确认，具体风险以服务端契约为准。"
        );
    }
}
