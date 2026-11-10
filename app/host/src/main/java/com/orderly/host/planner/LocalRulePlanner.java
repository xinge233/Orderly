package com.orderly.host.planner;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线本地规则计划器。
 *
 * <p>它不是外部大模型，只把受支持的演示句式转换为结构化工具调用。任何
 * 无法完整识别的输入都会返回澄清问题，而不是猜测执行。</p>
 */
@Component
public class LocalRulePlanner {

    private static final Pattern CREATE_DIRECTORY = Pattern.compile(
            "^(?:创建|新建)(?:目录|文件夹)\\s*[：:]?\\s*(.+)$"
    );
    private static final Pattern MOVE = Pattern.compile(
            "^移动\\s+(.+?)\\s+到\\s+(.+)$"
    );
    private static final Pattern TRASH = Pattern.compile(
            "^(?:删除|移入回收站)\\s*[：:]?\\s*(.+)$"
    );
    private static final Pattern TODO_WITH_DUE = Pattern.compile(
            "^创建待办\\s+(.+?)\\s+截止\\s+(.+)$"
    );
    private static final Pattern TODO_WITH_DUE_LABEL = Pattern.compile(
            "^创建待办\\s*[：:]\\s*(.+?)\\s*[；;]\\s*截止\\s*[：:]?\\s*(.+)$"
    );
    private static final Pattern TODO = Pattern.compile(
            "^创建待办\\s*[：:]?\\s*(.+)$"
    );
    private static final Pattern CALENDAR = Pattern.compile(
            "^(?:修改|调整|把|将)?\\s*日程\\s+(.+?)\\s+(?:改到|改为|调整到)\\s+(.+)$"
    );

    public PlannerResult draft(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isBlank()) {
            return question("请描述要完成的文件、待办或日程操作。");
        }

        // 界面示例把截止时间写在同一个待办语句中，先整体识别，避免把“截止”
        // 误当成另一条独立意图。
        Matcher labeledTodo = TODO_WITH_DUE_LABEL.matcher(normalized);
        if (labeledTodo.matches()) {
            PlannerStep todo = step("todo.create", Map.of(
                    "title", labeledTodo.group(1).trim(),
                    "dueAt", labeledTodo.group(2).trim()
            ));
            return new PlannerResult(
                    "PLAN",
                    "已生成 1 步计划，请检查后确认执行。",
                    defaultAssumptions(),
                    "共 1 步，其中写入 1 步；确认前不会产生真实变更。",
                    0.96,
                    List.of(todo)
            );
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
            PlannerStep parsed = parsePart(part);
            if (parsed == null) {
                return question("无法把“" + part + "”转换为受支持的受控操作。"
                        + "请改用：创建目录、移动 A 到 B、删除 A、创建待办、修改日程。");
            }
            steps.add(parsed);
        }
        if (steps.isEmpty()) {
            return question("没有识别到可预览的操作步骤。");
        }

        long writes = steps.stream()
                .filter(step -> !step.actionId().equals("fs.list"))
                .count();
        long deletes = steps.stream().filter(step -> step.actionId().equals("fs.trash")).count();
        String impact = "共 " + steps.size() + " 步，其中写入 " + writes + " 步"
                + (deletes > 0 ? "，删除类 " + deletes + " 步" : "")
                + "；确认前不会产生真实变更。";
        return new PlannerResult(
                "PLAN",
                "已生成 " + steps.size() + " 步计划，请检查后确认执行。",
                defaultAssumptions(),
                impact,
                0.96,
                List.copyOf(steps)
        );
    }

    private PlannerStep parsePart(String part) {
        Matcher matcher = CREATE_DIRECTORY.matcher(part);
        if (matcher.matches()) {
            return step("fs.mkdir", Map.of("path", cleanPath(matcher.group(1))));
        }
        matcher = MOVE.matcher(part);
        if (matcher.matches()) {
            return step("fs.move", Map.of(
                    "source", cleanPath(matcher.group(1)),
                    "target", cleanPath(matcher.group(2))
            ));
        }
        matcher = TRASH.matcher(part);
        if (matcher.matches()) {
            return step("fs.trash", Map.of("path", cleanPath(matcher.group(1))));
        }
        matcher = TODO_WITH_DUE.matcher(part);
        if (matcher.matches()) {
            return step("todo.create", Map.of(
                    "title", matcher.group(1).trim(),
                    "dueAt", matcher.group(2).trim()
            ));
        }
        matcher = TODO.matcher(part);
        if (matcher.matches()) {
            return step("todo.create", Map.of("title", matcher.group(1).trim()));
        }
        matcher = CALENDAR.matcher(part);
        if (matcher.matches()) {
            return step("calendar.reschedule", Map.of(
                    "title", matcher.group(1).trim(),
                    "newStartAt", matcher.group(2).trim()
            ));
        }
        return null;
    }

    private String cleanPath(String value) {
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank() || Path.of(path).isAbsolute() || path.equals("..")
                || path.startsWith("../") || path.contains("/../")) {
            throw new IllegalArgumentException("只允许工作区内的相对路径：" + value);
        }
        return path;
    }

    private PlannerStep step(String actionId, Map<String, Object> arguments) {
        return new PlannerStep(actionId, Map.copyOf(arguments));
    }

    private PlannerResult question(String message) {
        return new PlannerResult(
                "QUESTION",
                message,
                List.of("本地规则计划器未连接外部模型。"),
                "未产生任何变更。",
                0.2,
                List.of()
        );
    }

    private List<String> defaultAssumptions() {
        return List.of(
                "所有操作仅发生在应用自管演示工作区。",
                "未指定时间时待办不设置截止时间，日程按给定文本保存。",
                "目标冲突时不覆盖已有对象；删除只进入项目回收站。"
        );
    }
}
