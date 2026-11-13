package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.kernel.ModuleRuntime;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
    private static final String UI_PREFIX = "orderly-ui/";
    private static final Map<String, String> UI_ASSETS = Map.of(
            "module.json", "application/json",
            "module.js", "application/javascript",
            "module.css", "text/css"
    );
    private static final long MAX_UI_ASSET_BYTES = 2L * 1024L * 1024L;

    private volatile List<ModuleContract> modules;
    private volatile Map<String, ModuleContract> modulesById;
    private volatile Map<String, ActionContract> actionsById;
    private volatile List<PlannerRuleContract> plannerRules;
    private volatile Map<String, OrderlyModule> executors;
    private volatile Map<String, Path> moduleJars;
    private volatile Map<String, ModuleSettingsDefinition> settingsByModule;
    private volatile URLClassLoader moduleClassLoader;
    private final Path moduleDirectory;
    private final List<URLClassLoader> moduleClassLoaders;
    private long generation;

    public ContractCatalog(
            ObjectMapper objectMapper,
            @Value("${orderly.modules-dir:target/orderly-modules}") String modulesDirectory
    ) {
        this.moduleDirectory = Path.of(modulesDirectory).toAbsolutePath().normalize();
        List<Path> jars = findModuleJars(moduleDirectory);

        Map<String, ModuleContract> loaded = new LinkedHashMap<>();
        Map<String, ActionContract> actions = new LinkedHashMap<>();
        Map<String, QueryContract> queries = new LinkedHashMap<>();
        Map<String, PlannerRuleContract> rules = new LinkedHashMap<>();
        Map<String, Path> jarsByModule = new LinkedHashMap<>();
        Map<String, ModuleSettingsDefinition> settings = new LinkedHashMap<>();
        for (Path jar : jars) {
            loadContracts(jar, objectMapper, loaded, actions, queries, rules, jarsByModule, settings);
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
        this.moduleClassLoaders = new ArrayList<>();
        if (candidate != null) {
            this.moduleClassLoaders.add(candidate);
        }
        this.modules = loaded.values().stream()
                .sorted(Comparator.comparingInt(ModuleContract::order)
                        .thenComparing(ModuleContract::moduleId))
                .toList();
        this.modulesById = Map.copyOf(loaded);
        this.actionsById = Map.copyOf(actions);
        this.plannerRules = List.copyOf(rules.values());
        this.executors = Map.copyOf(loadedExecutors);
        this.moduleJars = Map.copyOf(jarsByModule);
        this.settingsByModule = Map.copyOf(settings);
    }

    public List<ModuleContract> modules() {
        return modules;
    }

    public List<ModuleContract> businessModules() {
        return modules.stream().filter(module -> module.system() == null).toList();
    }

    public List<ModuleContract> systemModules() {
        return modules.stream().filter(module -> module.system() != null).toList();
    }

    public boolean isSystemModule(String moduleId) {
        ModuleContract module = modulesById.get(moduleId);
        return module != null && module.system() != null;
    }

    public ModuleSettingsDefinition settingsFor(String moduleId) {
        return settingsByModule.getOrDefault(moduleId, ModuleSettingsDefinition.empty());
    }

    public Optional<ModuleArtifact> moduleArtifact(String moduleId) {
        Path jar = moduleJars.get(moduleId);
        if (jar == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ModuleArtifact(
                    moduleId,
                    jar,
                    jar.getFileName().toString(),
                    Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis()
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块 JAR 摘要失败：" + jar, exception);
        }
    }

    /** 启动阶段必须恰好存在一个受保护的控制模块。 */
    public synchronized void validateSystemModules() {
        List<ModuleContract> system = systemModules();
        if (system.size() != 1) {
            throw new IllegalStateException(
                    "宿主必须且只能加载一个 MODULE_CONTROL 系统模块，当前数量：" + system.size()
            );
        }
        ModuleContract control = system.getFirst();
        if (!"module_control".equals(control.moduleId())
                || control.system() == null
                || !ModuleSystemMetadata.CONTROL_ROLE.equals(control.system().role())
                || !control.system().protectedModule()) {
            throw new IllegalStateException(
                    "系统模块必须是受保护的 module_control/MODULE_CONTROL"
            );
        }
    }

    public Path moduleDirectory() {
        return moduleDirectory;
    }

    public Optional<ModuleContract> findModule(String moduleId) {
        return Optional.ofNullable(modulesById.get(moduleId));
    }

    public ModuleContract requireModule(String moduleId) {
        return findModule(moduleId).orElseThrow(
                () -> new IllegalArgumentException("模块未注册：" + moduleId)
        );
    }

    public List<PlannerRuleContract> plannerRules() {
        return plannerRules;
    }

    /** 由宿主启动阶段统一调用各模块的幂等初始化。 */
    public synchronized void initializeModules(ModuleRuntime runtime) {
        for (ModuleContract module : modules) {
            requireExecutor(module.moduleId()).initialize(runtime);
        }
    }

    /**
     * 预检一个新业务模块，不修改当前目录快照。失败时调用方无需回滚。
     */
    public synchronized PreparedModule prepareModule(
            Path jar,
            ModuleRuntime runtime,
            ObjectMapper objectMapper
    ) {
        Path candidateJar = requireRegularJar(jar.toAbsolutePath().normalize());
        Map<String, ModuleContract> loaded = new LinkedHashMap<>(modulesById);
        Map<String, ActionContract> actions = new LinkedHashMap<>(actionsById);
        Map<String, QueryContract> queries = new LinkedHashMap<>();
        Map<String, PlannerRuleContract> rules = new LinkedHashMap<>();
        Map<String, Path> jarsByModule = new LinkedHashMap<>(moduleJars);
        Map<String, ModuleSettingsDefinition> settings = new LinkedHashMap<>(settingsByModule);
        modules.forEach(module -> module.queries().forEach(query -> queries.put(query.id(), query)));
        modules.forEach(module -> module.plannerRules().forEach(rule -> rules.put(rule.id(), rule)));

        Set<String> existingIds = new LinkedHashSet<>(modulesById.keySet());
        loadContracts(
                candidateJar,
                objectMapper,
                loaded,
                actions,
                queries,
                rules,
                jarsByModule,
                settings
        );
        List<ModuleContract> addedContracts = loaded.values().stream()
                .filter(module -> !existingIds.contains(module.moduleId()))
                .toList();
        if (addedContracts.size() != 1) {
            throw new IllegalStateException(
                    "刷新 JAR 必须且只能声明一个新的模块契约：" + candidateJar.getFileName()
            );
        }
        ModuleContract addedModule = addedContracts.getFirst();
        if (addedModule.system() != null) {
            throw new IllegalStateException(
                    "刷新只允许追加业务模块，拒绝系统模块候选：" + addedModule.moduleId()
            );
        }

        URLClassLoader candidateLoader = null;
        Map<String, OrderlyModule> loadedExecutors = new LinkedHashMap<>(executors);
        try {
            candidateLoader = new URLClassLoader(
                    new URL[]{toUrl(candidateJar)},
                    moduleClassLoader == null ? OrderlyModule.class.getClassLoader() : moduleClassLoader
            );
            Set<String> addedExecutorIds = new LinkedHashSet<>();
            for (OrderlyModule module : ServiceLoader.load(OrderlyModule.class, candidateLoader)) {
                String moduleId = module.moduleId();
                if (moduleId == null || moduleId.isBlank()) {
                    throw new IllegalStateException("模块执行器未声明 moduleId");
                }
                if (existingIds.contains(moduleId)) {
                    continue;
                }
                if (loadedExecutors.putIfAbsent(moduleId, module) != null
                        || !addedExecutorIds.add(moduleId)) {
                    throw new IllegalStateException("检测到重复模块执行器：" + moduleId);
                }
            }
            Set<String> addedContractIds = addedContracts.stream()
                    .map(ModuleContract::moduleId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!addedContractIds.equals(addedExecutorIds)) {
                throw new IllegalStateException(
                        "新增模块契约与执行器不一致，契约=" + addedContractIds + "，执行器=" + addedExecutorIds
                );
            }
            for (ModuleContract module : addedContracts) {
                loadedExecutors.get(module.moduleId()).initialize(runtime);
            }
        } catch (RuntimeException | Error exception) {
            closeQuietly(candidateLoader);
            throw exception instanceof IllegalStateException illegal
                    ? illegal
                    : new IllegalStateException("加载新增模块执行器失败", exception);
        }

        return new PreparedModule(
                this,
                addedModule,
                candidateLoader,
                loaded,
                actions,
                rules,
                loadedExecutors,
                jarsByModule,
                settings,
                generation
        );
    }

    private synchronized void commitPrepared(PreparedModule prepared) {
        if (prepared.owner != this) {
            throw new IllegalArgumentException("模块预检结果不属于当前宿主");
        }
        if (prepared.closed || prepared.committed || prepared.rolledBack) {
            throw new IllegalStateException("模块预检结果状态不允许提交");
        }
        if (prepared.baselineGeneration != generation) {
            throw new IllegalStateException("模块目录已变化，拒绝提交过期预检结果");
        }

        moduleClassLoader = prepared.candidateLoader;
        moduleClassLoaders.add(prepared.candidateLoader);
        modules = prepared.modules;
        modulesById = prepared.modulesById;
        actionsById = prepared.actionsById;
        plannerRules = prepared.plannerRules;
        executors = prepared.executors;
        moduleJars = prepared.moduleJars;
        settingsByModule = prepared.settingsByModule;
        generation++;
        prepared.committed = true;
    }

    private synchronized void rollbackPrepared(PreparedModule prepared) {
        if (prepared.owner != this || !prepared.committed) {
            throw new IllegalStateException("模块预检结果尚未提交，不能回滚");
        }
        if (prepared.closed || prepared.rolledBack || prepared.baselineGeneration + 1 != generation) {
            throw new IllegalStateException("模块目录已变化，拒绝回滚过期提交");
        }

        moduleClassLoader = prepared.previousClassLoader;
        modules = prepared.previousModules;
        modulesById = prepared.previousModulesById;
        actionsById = prepared.previousActions;
        plannerRules = prepared.previousPlannerRules;
        executors = prepared.previousExecutors;
        moduleJars = prepared.previousModuleJars;
        settingsByModule = prepared.previousSettings;
        moduleClassLoaders.remove(prepared.candidateLoader);
        closeQuietly(prepared.candidateLoader);
        generation--;
        prepared.rolledBack = true;
        prepared.closed = true;
    }

    /**
     * 从模块 JAR 读取严格白名单内的 UI 资源。
     *
     * @return 资源不存在或不属于模块时为 empty
     */
    public Optional<ModuleUiAsset> readUiAsset(String moduleId, String assetName) {
        if (!modulesById.containsKey(moduleId) || !UI_ASSETS.containsKey(assetName)) {
            return Optional.empty();
        }
        Path jarPath = moduleJars.get(moduleId);
        if (jarPath == null) {
            return Optional.empty();
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(UI_PREFIX + assetName);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            if (entry.getSize() > MAX_UI_ASSET_BYTES) {
                throw new IllegalStateException("模块 UI 资源超过大小上限：" + moduleId + "/" + assetName);
            }
            try (var stream = jar.getInputStream(entry)) {
                byte[] content = stream.readNBytes((int) MAX_UI_ASSET_BYTES + 1);
                if (content.length > MAX_UI_ASSET_BYTES) {
                    throw new IllegalStateException("模块 UI 资源超过大小上限：" + moduleId + "/" + assetName);
                }
                return Optional.of(new ModuleUiAsset(
                        UI_ASSETS.get(assetName),
                        content
                ));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块 UI 资源失败：" + moduleId + "/" + assetName, exception);
        }
    }

    private ModuleSettingsDefinition readSettings(
            JarFile jar,
            String moduleId,
            ObjectMapper objectMapper
    ) {
        JarEntry manifestEntry = jar.getJarEntry(UI_PREFIX + "module.json");
        if (manifestEntry == null || manifestEntry.isDirectory()) {
            throw new IllegalStateException("模块缺少 UI 清单：" + moduleId);
        }
        if (manifestEntry.getSize() > MAX_UI_ASSET_BYTES) {
            throw new IllegalStateException("模块 UI 清单超过大小上限：" + moduleId);
        }
        try (var stream = jar.getInputStream(manifestEntry)) {
            JsonNode manifest = objectMapper.readTree(stream);
            return ModuleSettingsDefinition.parse(manifest.path("settings"), objectMapper);
        } catch (IOException exception) {
            throw new IllegalStateException("读取模块 UI 设置声明失败：" + moduleId, exception);
        }
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
        List<URLClassLoader> loaders = new ArrayList<>(moduleClassLoaders);
        Collections.reverse(loaders);
        IOException failure = null;
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
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
            Map<String, ActionContract> actions,
            Map<String, QueryContract> queries,
            Map<String, PlannerRuleContract> plannerRules,
            Map<String, Path> jarsByModule,
            Map<String, ModuleSettingsDefinition> settingsByModule
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
                    module = parseAndValidateModule(objectMapper.readTree(stream));
                }
                if (loaded.putIfAbsent(module.moduleId(), module) != null) {
                    throw new IllegalStateException("检测到重复模块契约：" + module.moduleId());
                }
                settingsByModule.put(module.moduleId(), readSettings(jar, module.moduleId(), objectMapper));
                jarsByModule.put(module.moduleId(), jarPath);
                for (ActionContract action : module.actions()) {
                    if (actions.putIfAbsent(action.id(), action) != null) {
                        throw new IllegalStateException("检测到重复动作契约：" + action.id());
                    }
                }
                for (QueryContract query : module.queries()) {
                    if (queries.putIfAbsent(query.id(), query) != null) {
                        throw new IllegalStateException("检测到重复查询契约：" + query.id());
                    }
                }
                for (PlannerRuleContract rule : module.plannerRules()) {
                    if (plannerRules.putIfAbsent(rule.id(), rule) != null) {
                        throw new IllegalStateException("检测到重复计划规则：" + rule.id());
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

    /**
     * 解析并校验一个模块的完整契约。
     *
     * <p>测试用固定样例复用与启动阶段完全相同的校验逻辑。</p>
     */
    ModuleContract parseAndValidateModule(JsonNode root) {
        ModuleContract module = parseModule(root);
        validateModule(module);
        for (QueryContract query : module.queries()) {
            validateQuery(module, query);
        }
        for (PlannerRuleContract rule : module.plannerRules()) {
            validatePlannerRule(module, rule);
        }
        return module;
    }

    private ModuleContract parseModule(JsonNode root) {
        requireText(root, "moduleId");
        requireText(root, "title");
        requireText(root, "description");
        if (!root.path("order").canConvertToInt()) {
            throw new IllegalStateException("模块契约缺少整数 order");
        }
        JsonNode rawActions = root.path("actions");
        if (!rawActions.isArray()) {
            throw new IllegalStateException("模块 actions 必须是数组");
        }
        List<ActionContract> actions = new ArrayList<>();
        for (JsonNode node : rawActions) {
            actions.add(parseAction(node));
        }
        JsonNode rawQueries = root.path("queries");
        if (!rawQueries.isArray()) {
            throw new IllegalStateException("模块 queries 必须是数组");
        }
        List<QueryContract> queries = new ArrayList<>();
        for (JsonNode node : rawQueries) {
            queries.add(parseQuery(node));
        }
        JsonNode rawPlannerRules = root.path("plannerRules");
        if (!rawPlannerRules.isArray()) {
            throw new IllegalStateException("模块 plannerRules 必须是数组");
        }
        List<PlannerRuleContract> plannerRules = new ArrayList<>();
        for (JsonNode node : rawPlannerRules) {
            plannerRules.add(parsePlannerRule(node));
        }
        ModuleSystemMetadata system = parseSystemMetadata(root.path("system"));
        return new ModuleContract(
                root.path("moduleId").asText(),
                root.path("title").asText(),
                root.path("description").asText(),
                root.path("order").asInt(),
                system,
                actions,
                queries,
                plannerRules
        );
    }

    private ModuleSystemMetadata parseSystemMetadata(JsonNode rawSystem) {
        if (rawSystem.isMissingNode() || rawSystem.isNull()) {
            return null;
        }
        if (!rawSystem.isObject()) {
            throw new IllegalStateException("模块 system 必须是对象");
        }
        String role = rawSystem.path("role").asText("");
        if (!ModuleSystemMetadata.CONTROL_ROLE.equals(role)) {
            throw new IllegalStateException("不支持的系统模块角色：" + role);
        }
        JsonNode protectedNode = rawSystem.get("protected");
        if (protectedNode == null || !protectedNode.isBoolean() || !protectedNode.asBoolean()) {
            throw new IllegalStateException("模块 system.protected 必须为 true");
        }
        return new ModuleSystemMetadata(
                role,
                true
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
        List<ParameterContract> parameters = parseParameters(node.path("parameters"), "动作");
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

    private QueryContract parseQuery(JsonNode node) {
        requireText(node, "id");
        requireText(node, "title");
        requireText(node, "description");
        return new QueryContract(
                node.path("id").asText(),
                node.path("title").asText(),
                node.path("description").asText(),
                parseParameters(node.path("parameters"), "查询")
        );
    }

    private PlannerRuleContract parsePlannerRule(JsonNode node) {
        requireText(node, "id");
        requireText(node, "actionId");
        requireText(node, "pattern");
        JsonNode rawArguments = node.path("argumentGroups");
        if (!rawArguments.isObject() || rawArguments.isEmpty()) {
            throw new IllegalStateException("计划规则 argumentGroups 必须是非空对象：" + node.path("id").asText());
        }
        Map<String, Integer> argumentGroups = new LinkedHashMap<>();
        rawArguments.fields().forEachRemaining(entry -> {
            if (!entry.getValue().canConvertToInt() || entry.getValue().asInt() < 1) {
                throw new IllegalStateException("计划规则捕获组必须是正整数：" + entry.getKey());
            }
            argumentGroups.put(entry.getKey(), entry.getValue().asInt());
        });
        Set<String> pathArguments = new java.util.LinkedHashSet<>();
        JsonNode rawPaths = node.path("pathArguments");
        if (!rawPaths.isArray()) {
            throw new IllegalStateException("计划规则 pathArguments 必须是数组：" + node.path("id").asText());
        }
        rawPaths.forEach(path -> {
            if (!path.isTextual() || path.asText().isBlank()) {
                throw new IllegalStateException("计划规则 pathArguments 只能包含非空文本");
            }
            pathArguments.add(path.asText());
        });
        return new PlannerRuleContract(
                node.path("id").asText(),
                node.path("actionId").asText(),
                node.path("pattern").asText(),
                argumentGroups,
                pathArguments,
                node.path("wholeGoal").asBoolean(false)
        );
    }

    private List<ParameterContract> parseParameters(JsonNode rawParameters, String owner) {
        if (!rawParameters.isArray()) {
            throw new IllegalStateException(owner + " parameters 必须是数组");
        }
        List<ParameterContract> parameters = new ArrayList<>();
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
        return parameters;
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

    private void validateQuery(ModuleContract module, QueryContract query) {
        if (!query.id().contains(".")) {
            throw new IllegalStateException("查询 ID 应包含模块命名空间：" + query.id());
        }
        requireUniqueParameters(query.id(), query.parameters());
    }

    private void validatePlannerRule(ModuleContract module, PlannerRuleContract rule) {
        if (!rule.id().contains(".")) {
            throw new IllegalStateException("计划规则 ID 应包含模块命名空间：" + rule.id());
        }
        ActionContract action = module.actions().stream()
                .filter(candidate -> candidate.id().equals(rule.actionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "计划规则引用了本模块不存在的动作：" + rule.id() + " -> " + rule.actionId()
                ));
        Set<String> actionParameters = action.parameters().stream()
                .map(ParameterContract::name)
                .collect(java.util.stream.Collectors.toSet());
        Pattern pattern;
        try {
            pattern = Pattern.compile(rule.pattern());
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException("计划规则正则有误：" + rule.id(), exception);
        }
        int groupCount = pattern.matcher("").groupCount();
        for (Map.Entry<String, Integer> entry : rule.argumentGroups().entrySet()) {
            if (!actionParameters.contains(entry.getKey())) {
                throw new IllegalStateException("计划规则映射了动作外参数：" + rule.id() + "/" + entry.getKey());
            }
            if (entry.getValue() > groupCount) {
                throw new IllegalStateException("计划规则捕获组越界：" + rule.id() + "/" + entry.getKey());
            }
        }
        for (String pathArgument : rule.pathArguments()) {
            boolean declared = action.parameters().stream().anyMatch(parameter ->
                    parameter.name().equals(pathArgument) && parameter.type().equals("string")
            );
            if (!declared || !rule.argumentGroups().containsKey(pathArgument)) {
                throw new IllegalStateException("计划规则路径参数未声明为字符串动作参数：" + rule.id() + "/" + pathArgument);
            }
        }
    }

    private void requireUniqueParameters(String ownerId, List<ParameterContract> parameters) {
        parameters.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ParameterContract::name,
                        java.util.stream.Collectors.counting()
                ))
                .forEach((name, count) -> {
                    if (count > 1) {
                        throw new IllegalStateException("参数重复：" + ownerId + "/" + name);
                    }
                });
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

    /** 可提交或回滚的单模块追加预检结果。 */
    public static final class PreparedModule implements AutoCloseable {
        private final ContractCatalog owner;
        private final ModuleContract module;
        private final URLClassLoader candidateLoader;
        private final List<ModuleContract> modules;
        private final Map<String, ModuleContract> modulesById;
        private final Map<String, ActionContract> actionsById;
        private final List<PlannerRuleContract> plannerRules;
        private final Map<String, OrderlyModule> executors;
        private final Map<String, Path> moduleJars;
        private final Map<String, ModuleSettingsDefinition> settingsByModule;
        private final long baselineGeneration;

        private final URLClassLoader previousClassLoader;
        private final List<ModuleContract> previousModules;
        private final Map<String, ModuleContract> previousModulesById;
        private final Map<String, ActionContract> previousActions;
        private final List<PlannerRuleContract> previousPlannerRules;
        private final Map<String, OrderlyModule> previousExecutors;
        private final Map<String, Path> previousModuleJars;
        private final Map<String, ModuleSettingsDefinition> previousSettings;

        private boolean committed;
        private boolean rolledBack;
        private boolean closed;

        private PreparedModule(
                ContractCatalog owner,
                ModuleContract module,
                URLClassLoader candidateLoader,
                Map<String, ModuleContract> loaded,
                Map<String, ActionContract> actions,
                Map<String, PlannerRuleContract> rules,
                Map<String, OrderlyModule> executors,
                Map<String, Path> moduleJars,
                Map<String, ModuleSettingsDefinition> settings,
                long baselineGeneration
        ) {
            this.owner = owner;
            this.module = module;
            this.candidateLoader = candidateLoader;
            this.modules = loaded.values().stream()
                    .sorted(Comparator.comparingInt(ModuleContract::order)
                            .thenComparing(ModuleContract::moduleId))
                    .toList();
            this.modulesById = Map.copyOf(loaded);
            this.actionsById = Map.copyOf(actions);
            this.plannerRules = List.copyOf(rules.values());
            this.executors = Map.copyOf(executors);
            this.moduleJars = Map.copyOf(moduleJars);
            this.settingsByModule = Map.copyOf(settings);
            this.baselineGeneration = baselineGeneration;

            this.previousClassLoader = owner.moduleClassLoader;
            this.previousModules = owner.modules;
            this.previousModulesById = owner.modulesById;
            this.previousActions = owner.actionsById;
            this.previousPlannerRules = owner.plannerRules;
            this.previousExecutors = owner.executors;
            this.previousModuleJars = owner.moduleJars;
            this.previousSettings = owner.settingsByModule;
        }

        public ModuleContract module() {
            return module;
        }

        public void commit() {
            owner.commitPrepared(this);
        }

        public void rollback() {
            owner.rollbackPrepared(this);
        }

        @Override
        public void close() {
            if (!committed && !closed) {
                closed = true;
                owner.closeQuietly(candidateLoader);
            }
        }
    }

    /** 当前加载模块对应的 JAR 摘要，供显式刷新判断是否发生替换。 */
    public record ModuleArtifact(
            String moduleId,
            Path path,
            String fileName,
            long size,
            long lastModifiedMillis
    ) {
    }
}
