package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.RiskLevel;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 从模块 JAR 聚合工具契约并绑定模块执行器。
 *
 * <p>契约是动作元数据的唯一真相源；Java 中只保存具体执行行为，不复制一份
 * 风险、参数或确认策略。</p>
 */
@Component
public class ContractCatalog {

    private static final Set<String> SUPPORTED_TYPES = Set.of("string", "boolean", "integer");

    private final List<ModuleContract> modules;
    private final Map<String, ModuleContract> modulesById;
    private final Map<String, ActionContract> actionsById;
    private final Map<String, OrderlyModule> executors;

    public ContractCatalog(ObjectMapper objectMapper) {
        Map<String, ModuleContract> loaded = new LinkedHashMap<>();
        Map<String, ActionContract> actions = new LinkedHashMap<>();

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:orderly-modules/*.json");
            if (resources.length == 0) {
                throw new IllegalStateException("没有发现任何 orderly-modules/*.json 契约");
            }
            for (Resource resource : resources) {
                ModuleContract module = parseModule(objectMapper.readTree(resource.getInputStream()));
                if (loaded.putIfAbsent(module.moduleId(), module) != null) {
                    throw new IllegalStateException("检测到重复模块契约：" + module.moduleId());
                }
                validateModule(module);
                for (ActionContract action : module.actions()) {
                    if (actions.putIfAbsent(action.id(), action) != null) {
                        throw new IllegalStateException("检测到重复动作契约：" + action.id());
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块契约失败", exception);
        }

        Map<String, OrderlyModule> loadedExecutors = new LinkedHashMap<>();
        for (OrderlyModule module : ServiceLoader.load(OrderlyModule.class)) {
            if (loadedExecutors.putIfAbsent(module.moduleId(), module) != null) {
                throw new IllegalStateException("检测到重复模块执行器：" + module.moduleId());
            }
        }
        if (!loaded.keySet().equals(loadedExecutors.keySet())) {
            throw new IllegalStateException(
                    "模块契约与执行器不一致，契约=" + loaded.keySet() + "，执行器=" + loadedExecutors.keySet()
            );
        }

        this.modules = loaded.values().stream()
                .sorted(Comparator.comparingInt(ModuleContract::order)
                        .thenComparing(ModuleContract::moduleId))
                .toList();
        this.modulesById = Map.copyOf(loaded);
        this.actionsById = Map.copyOf(actions);
        this.executors = Map.copyOf(loadedExecutors);
    }

    public List<ModuleContract> modules() {
        return modules;
    }

    public Optional<ActionContract> findAction(String actionId) {
        return Optional.ofNullable(actionsById.get(actionId));
    }

    public ActionContract requireAction(String actionId) {
        return findAction(actionId).orElseThrow(
                () -> new IllegalArgumentException("契约中不存在动作：" + actionId)
        );
    }

    public OrderlyModule requireExecutor(String moduleId) {
        OrderlyModule executor = executors.get(moduleId);
        if (executor == null) {
            throw new IllegalArgumentException("模块未注册：" + moduleId);
        }
        return executor;
    }

    public String moduleIdForAction(String actionId) {
        for (ModuleContract module : modules) {
            if (module.actions().stream().anyMatch(action -> action.id().equals(actionId))) {
                return module.moduleId();
            }
        }
        throw new IllegalArgumentException("动作没有归属模块：" + actionId);
    }

    private ModuleContract parseModule(JsonNode root) {
        requireText(root, "moduleId");
        requireText(root, "title");
        requireText(root, "description");
        if (!root.path("order").canConvertToInt()) {
            throw new IllegalStateException("模块契约缺少整数 order");
        }
        JsonNode rawActions = root.path("actions");
        if (!rawActions.isArray() || rawActions.isEmpty()) {
            throw new IllegalStateException("模块契约必须至少声明一个动作");
        }
        List<ActionContract> actions = new ArrayList<>();
        for (JsonNode action : rawActions) {
            actions.add(parseAction(action));
        }
        return new ModuleContract(
                root.path("moduleId").asText(),
                root.path("title").asText(),
                root.path("description").asText(),
                root.path("order").asInt(),
                actions
        );
    }

    private ActionContract parseAction(JsonNode node) {
        requireText(node, "id");
        requireText(node, "title");
        requireText(node, "description");
        requireText(node, "risk");
        RiskLevel risk;
        try {
            risk = RiskLevel.valueOf(node.path("risk").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("动作风险等级非法：" + node.path("id").asText());
        }
        List<ParameterContract> parameters = new ArrayList<>();
        JsonNode rawParameters = node.path("parameters");
        if (!rawParameters.isArray()) {
            throw new IllegalStateException("动作 parameters 必须是数组");
        }
        for (JsonNode parameter : rawParameters) {
            requireText(parameter, "name");
            requireText(parameter, "type");
            if (!SUPPORTED_TYPES.contains(parameter.path("type").asText())) {
                throw new IllegalStateException("不支持的参数类型：" + parameter.path("type").asText());
            }
            parameters.add(new ParameterContract(
                    parameter.path("name").asText(),
                    parameter.path("type").asText(),
                    parameter.path("required").asBoolean(false),
                    parameter.path("description").asText("")
            ));
        }
        return new ActionContract(
                node.path("id").asText(),
                node.path("title").asText(),
                node.path("description").asText(),
                risk,
                node.path("confirmationRequired").asBoolean(false),
                node.path("confirmationPhrase").asText(""),
                List.copyOf(parameters)
        );
    }

    private void validateModule(ModuleContract module) {
        for (ActionContract action : module.actions()) {
            if (!action.id().contains(".")) {
                throw new IllegalStateException("动作 ID 应包含模块命名空间：" + action.id());
            }
            if (action.risk() != RiskLevel.READ_ONLY && !action.confirmationRequired()) {
                throw new IllegalStateException("写或删除动作必须要求确认：" + action.id());
            }
            if (action.risk() == RiskLevel.DELETE && action.confirmationPhrase().isBlank()) {
                throw new IllegalStateException("删除动作必须声明强确认词：" + action.id());
            }
            action.parameters().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ParameterContract::name,
                            java.util.stream.Collectors.counting()
                    ))
                    .forEach((name, count) -> {
                        if (count > 1) {
                            throw new IllegalStateException("动作参数重复：" + action.id() + "/" + name);
                        }
                    });
        }
    }

    private void requireText(JsonNode node, String field) {
        if (node.path(field).asText("").isBlank()) {
            throw new IllegalStateException("契约缺少文本字段：" + field);
        }
    }
}
