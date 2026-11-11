package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.OrderlyModule;
import com.orderly.kernel.RiskLevel;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 从外部模块目录聚合工具契约并绑定模块执行器。
 *
 * <p>宿主不编译期依赖任何具体模块。模块 JAR 是本地受信任的可执行文件，
 * 由构建脚本放入运行时目录；启动时只扫描一次，不提供热加载或远程下载。</p>
 */
@Component
public class ContractCatalog implements AutoCloseable {

    private static final Set<String> SUPPORTED_TYPES = Set.of("string", "boolean", "integer");
    private static final String CONTRACT_PREFIX = "orderly-modules/";

    private final List<ModuleContract> modules;
    private final Map<String, ModuleContract> modulesById;
    private final Map<String, ActionContract> actionsById;
    private final Map<String, OrderlyModule> executors;
    private final URLClassLoader moduleClassLoader;
    private final Path moduleDirectory;

    public ContractCatalog(
            ObjectMapper objectMapper,
            @Value("${orderly.modules-dir:target/orderly-modules}") String modulesDirectory
    ) {
        this.moduleDirectory = Path.of(modulesDirectory).toAbsolutePath().normalize();
        List<Path> jars = findModuleJars(moduleDirectory);

        Map<String, ModuleContract> loaded = new LinkedHashMap<>();
        Map<String, ActionContract> actions = new LinkedHashMap<>();
        for (Path jar : jars) {
            loadContracts(jar, objectMapper, loaded, actions);
        }

        URLClassLoader candidate = null;
        Map<String, OrderlyModule> loadedExecutors = new LinkedHashMap<>();
        try {
            if (!jars.isEmpty()) {
                URL[] urls = jars.stream().map(this::toUrl).toArray(URL[]::new);
                candidate = new URLClassLoader(urls, OrderlyModule.class.getClassLoader());
                for (OrderlyModule module : ServiceLoader.load(OrderlyModule.class, candidate)) {
                    String moduleId = module.moduleId();
                    if (moduleId == null || moduleId.isBlank()) {
                        throw new IllegalStateException("模块执行器未声明 moduleId");
                    }
                    if (loadedExecutors.putIfAbsent(moduleId, module) != null) {
                        throw new IllegalStateException("检测到重复模块执行器：" + moduleId);
                    }
                }
            }
        } catch (RuntimeException | Error exception) {
            closeQuietly(candidate);
            throw exception instanceof IllegalStateException illegal
                    ? illegal
                    : new IllegalStateException("加载外部模块执行器失败", exception);
        }

        if (!loaded.keySet().equals(loadedExecutors.keySet())) {
            closeQuietly(candidate);
            throw new IllegalStateException(
                    "模块契约与执行器不一致，契约=" + loaded.keySet() + "，执行器=" + loadedExecutors.keySet()
            );
        }

        this.moduleClassLoader = candidate;
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

    public Path moduleDirectory() {
        return moduleDirectory;
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

    @Override
    @PreDestroy
    public void close() throws IOException {
        if (moduleClassLoader != null) {
            moduleClassLoader.close();
        }
    }

    private List<Path> findModuleJars(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("模块目录必须是普通本地目录：" + directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::requireRegularJar)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描模块目录失败：" + directory, exception);
        }
    }

    private Path requireRegularJar(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("模块 JAR 必须位于模块目录内且不能是符号链接：" + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private void loadContracts(
            Path jarPath,
            ObjectMapper objectMapper,
            Map<String, ModuleContract> loaded,
            Map<String, ActionContract> actions
    ) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            List<JarEntry> contractEntries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith(CONTRACT_PREFIX))
                    .filter(entry -> entry.getName().endsWith(".json"))
                    .filter(entry -> entry.getName().indexOf('/', CONTRACT_PREFIX.length()) < 0)
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
            for (JarEntry entry : contractEntries) {
                ModuleContract module;
                try (var stream = jar.getInputStream(entry)) {
                    module = parseModule(objectMapper.readTree(stream));
                }
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
            throw new IllegalStateException("读取模块 JAR 契约失败：" + jarPath.getFileName(), exception);
        }
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException exception) {
            throw new IllegalStateException("模块 JAR 路径非法：" + path.getFileName(), exception);
        }
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
            throw new IllegalStateException("模块 actions 必须是非空数组");
        }
        List<ActionContract> actions = new ArrayList<>();
        for (JsonNode node : rawActions) {
            actions.add(parseAction(node));
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

    private void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // 初始化失败时保留原始异常，关闭失败不覆盖根因。
        }
    }
}