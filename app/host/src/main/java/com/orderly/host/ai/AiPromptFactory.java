package com.orderly.host.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ActionContract;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ParameterContract;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从模块契约生成模型可见工具卡和固定运行约束。 */
@Component
public class AiPromptFactory {

    private final ObjectMapper objectMapper;

    public AiPromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt(List<ModuleContract> modules, boolean allowDeleteDirect) {
        return systemPrompt(modules, allowDeleteDirect, ZonedDateTime.now());
    }

    String systemPrompt(
            List<ModuleContract> modules,
            boolean allowDeleteDirect,
            ZonedDateTime now
    ) {
        String catalog;
        try {
            catalog = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toolCatalog(modules));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 AI 工具卡", exception);
        }
        return """
                你是归序 Orderly 的受控工作流意图解释器。
                你只能把用户的自然语言目标转换为结构化结果，不生成文章、说明文或闲聊内容。
                你只能使用下面工具卡中声明的 moduleId、actionId、参数和描述，不能发明工具或参数。
                所有写入、删除、快照、确认和回滚由宿主负责；你不能直接操作文件或数据库。

                当前本地日期：%s
                当前本地时间：%s
                当前时区：%s

                返回必须是一个 JSON 对象，不要 Markdown，不要代码围栏。
                kind 只能是 ACTIONS、PLAN、QUESTION。

                ACTIONS 示例：
                {"kind":"ACTIONS","message":"执行明确操作","actions":[{"moduleId":"<moduleId>","actionId":"<actionId>","arguments":{}}]}

                PLAN 示例：
                {"kind":"PLAN","message":"请审查计划","actions":[{"moduleId":"<moduleId>","actionId":"<actionId>","arguments":{}}]}

                QUESTION 示例：
                {"kind":"QUESTION","message":"需要补充目标","question":{"prompt":"要整理哪个目录？","options":["下载目录","桌面"]}}

                决策规则：
                1. 默认返回 PLAN，让用户审查后再执行。
                2. 只有用户指令和参数都非常明确、无需假设时，才可以返回 ACTIONS。
                3. 目标指代不明、缺少参数、存在多个候选或工具能力不足时，返回 QUESTION。
                4. QUESTION 必须给出具体问题和可选项。
                5. 时间参数必须结合当前本地日期、时间和时区转换为带偏移量的 ISO-8601，例如 2026-11-17T21:00:00+08:00；用户无需手工填写技术格式，日期或时间含义不明确时必须返回 QUESTION。
                6. DELETE 动作默认只能放进 PLAN；当前直执删除策略：%s。
                7. 多步 ACTIONS 按顺序执行，后续动作不能直接调用模块，只能由宿主执行。
                8. 不要输出工具卡之外的字段。

                可用工具卡：
                %s
                """.formatted(
                now.toLocalDate(),
                now.toOffsetDateTime(),
                now.getZone(),
                allowDeleteDirect ? "允许明确意图直执" : "禁止直执",
                catalog
        );
    }

    private List<Map<String, Object>> toolCatalog(List<ModuleContract> modules) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (ModuleContract module : modules) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (ActionContract action : module.actions()) {
                if (!action.aiCallable()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("actionId", action.id());
                item.put("title", action.title());
                item.put("description", action.description());
                item.put("risk", action.risk().name());
                item.put("parameters", action.parameters().stream()
                        .map(this::parameter)
                        .toList());
                actions.add(item);
            }
            if (actions.isEmpty()) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("moduleId", module.moduleId());
            card.put("title", module.title());
            card.put("description", module.description());
            card.put("actions", actions);
            cards.add(card);
        }
        return cards;
    }

    private Map<String, Object> parameter(ParameterContract parameter) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", parameter.name());
        item.put("type", parameter.type());
        item.put("required", parameter.required());
        item.put("description", parameter.description());
        return item;
    }
}
