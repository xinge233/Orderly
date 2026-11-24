package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.modulecontrol.ModuleControlInterfaces;
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
 * <p>模块 JAR 内的 {@code orderly-modules/*.json} 是 Action、Query、参数、风险和
 * 本地计划规则的唯一机器可读真相源；本类只解析、校验并建立索引，不维护第二套工具
 * 定义。宿主不编译期依赖任何具体模块，而是通过 {@link URLClassLoader} 与
 * {@link ServiceLoader} 把契约和执行器绑定。模块 JAR 是本地受信任的可执行文件，
 * 启动时只扫描一次，不提供热替换、卸载或远程下载。</p>
 *
 * <p>显式刷新只允许追加一个新业务模块。候选 JAR 先完成加载、初始化和全部校验，
 * 再通过代次检查提交；提交后的快照可整体回滚，失败候选的类加载器会被关闭。</p>
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

    /**
     * 扫描运行模块目录，加载全部契约和执行器，并建立启动快照。
     *
     * <p>契约模块集合必须与 {@link ServiceLoader} 暴露的执行器集合一致，否则启动
     * 失败。模块目录不存在表示零业务模块；目录存在时必须是普通目录，JAR 必须是
     * 非符号链接的普通文件。</p>
     */
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

    /** 读取当前已加载模块 JAR 的文件名、大小和修改时间摘要。 */
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

    /**
     * 启动阶段必须恰好存在一个控制模块，并允许登记过的受保护系统服务。
     *
     * <p>{@code module_system_} 前缀和系统元数据必须同时出现；所有系统模块还必须
     * 命中宿主内置准入清单。用户自行放入的 JAR 不能仅靠修改契约取得系统模块身份。</p>
     */
    public synchronized void validateSystemModules() {
        List<ModuleContract> system = systemModules();
        List<ModuleContract> controls = system.stream()
                .filter(module -> module.system() != null
                        && ModuleSystemMetadata.CONTROL_ROLE.equals(module.system().role()))
                .toList();
        if (controls.size() != 1) {
            throw new IllegalStateException(
                    "宿主必须且只能加载一个 MODULE_CONTROL 系统模块，当前数量："
                            + controls.size()
            );
        }
        ModuleContract control = controls.getFirst();
        if (!ModuleControlInterfaces.MODULE_ID.equals(control.moduleId())) {
            throw new IllegalStateException(
                    "控制模块 ID 必须是 " + ModuleControlInterfaces.MODULE_ID
                            + "，实际：" + control.moduleId()
            );
        }
        for (ModuleContract module : system) {
            if (!SystemModulePolicy.hasReservedPrefix(module.moduleId())) {
                throw new IllegalStateException(
                        "受保护系统模块必须使用 module_system_ 前缀：" + module.moduleId()
                );
            }
            if (!SystemModulePolicy.isBuiltIn(module.moduleId())) {
                throw new IllegalStateException(
                        "module_system_ 前缀只允许宿主内置模块使用：" + module.moduleId()
                );
            }
            if (!SystemModulePolicy.matchesBuiltIn(module.moduleId(), module.system())) {
                throw new IllegalStateException(
                        "系统模块角色或能力与宿主内置登记不一致：" + module.moduleId()
                );
            }
        }
        validateControlInterfaces(control);
    }

    /**
     * 限制系统控制模块只能声明固定治理接口，并禁止本地计划规则或非写入系统动作。
     */
    private void validateControlInterfaces(ModuleContract control) {
        Set<String> actionIds = control.actions().stream()
                .map(ActionContract::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> queryIds = control.queries().stream()
                .map(QueryContract::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!ModuleControlInterfaces.ACTION_IDS.equals(actionIds)) {
            throw new IllegalStateException(
                    "控制模块只能声明已批准的系统治理 Action：" + actionIds
            );
        }
        if (!ModuleControlInterfaces.QUERY_IDS.equals(queryIds)) {
            throw new IllegalStateException(
                    "控制模块只能声明已批准的系统治理 Query：" + queryIds
            );
        }
        if (!control.plannerRules().isEmpty()) {
            throw new IllegalStateException("控制模块不允许声明本地计划规则");
        }
        control.actions().forEach(action -> {
            if (action.risk() != RiskLevel.WRITE || !action.confirmationRequired()) {
                throw new IllegalStateException(
                        "系统治理 Action 必须是需要确认的 WRITE 动作：" + action.id()
                );
            }
        });
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
     * 预检一个新业务模块，不修改当前对外契约快照。
     *
     * <p>候选 JAR 必须恰好声明一个新模块，静态契约与新增执行器必须一一对应；
     * 系统模块、重复 ID 和契约冲突均被拒绝。失败时调用方无需回滚，因为当前快照
     * 尚未改变。</p>
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

    /**
     * 原子替换当前运行快照，并保留前一快照供显式刷新失败时回滚。
     */
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

    /**
     * 在快照代次仍匹配时恢复提交前的契约、执行器和类加载器。
     */
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

    /**
     * 从模块 JAR 的 {@code orderly-ui/module.json} 读取并校验设置声明。
     */
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

    /** 根据已加载契约解析动作所属模块 ID。 */
    public String moduleIdForAction(String actionId) {
        for (ModuleContract module : modules) {
            if (module.actions().stream().anyMatch(action -> action.id().equals(actionId))) {
                return module.moduleId();
            }
        }
        throw new IllegalArgumentException("动作没有归属模块：" + actionId);
    }

    /**
     * 逆序关闭刷新过程中创建的类加载器；多个关闭错误会合并后抛出。
     */
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

    /**
     * 稳定扫描模块目录中的直接 JAR 文件。
     *
     * <p>缺失目录表示没有模块；符号链接目录直接被拒绝，文件按名称排序以保证启动
     * 快照可重复。实现不递归进入子目录。</p>
     */
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

    /** 要求候选 JAR 是普通本地文件而非符号链接，并返回规范化绝对路径。 */
    private Path requireRegularJar(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("模块 JAR 必须位于模块目录内且不能是符号链接：" + path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * 解析一个 JAR 中的模块契约、UI 设置和全局唯一索引。
     *
     * <p>只读取 JAR 根下 {@code orderly-modules/*.json}。模块、Action、Query 和
     * 计划规则 ID 均写入共享映射，通过 {@code putIfAbsent} 拒绝跨模块重复。</p>
     */
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
            if (contractEntries.size() != 1) {
                throw new IllegalStateException(
                        "模块 JAR 必须且只能声明一个契约：" + jarPath.getFileName()
                );
            }
            for (JarEntry entry : contractEntries) {
                ModuleContract module;
                try (var stream = jar.getInputStream(entry)) {
                    module = parseAndValidateModule(objectMapper.readTree(stream));
                }
                String sourceName = sourceName(jarPath.getFileName().toString());
                if (!sourceName.equals(module.moduleId())) {
                    throw new IllegalStateException(
                            "模块目录名、JAR 名和契约 moduleId 必须一致："
                                    + jarPath.getFileName() + " -> " + module.moduleId()
                    );
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

    private String sourceName(String jarFileName) {
        return jarFileName.endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - 4)
                : jarFileName;
    }

    /**
     * 解析并校验一个模块的完整契约。
     *
     * <p>测试用固定样例复用与启动阶段完全相同的校验逻辑。</p>
     */
    public ModuleContract parseAndValidateModule(JsonNode root) {
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

    /**
     * 解析模块契约的必填字段和三个能力数组；结构错误在启动或刷新时失败。
     */
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
        String moduleId = root.path("moduleId").asText();
        ModuleSystemMetadata system = parseSystemMetadata(root.path("system"));
        if (system == null && SystemModulePolicy.hasReservedPrefix(moduleId)) {
            throw new IllegalStateException(
                    "module_system_ 前缀只允许声明 system 元数据的宿主内置模块使用："
                            + moduleId
            );
        }
        if (system != null && !SystemModulePolicy.hasReservedPrefix(moduleId)) {
            throw new IllegalStateException(
                    "受保护系统模块必须使用 module_system_ 前缀：" + moduleId
            );
        }
        return new ModuleContract(
                moduleId,
                root.path("title").asText(),
                root.path("description").asText(),
                root.path("order").asInt(),
                system,
                actions,
                queries,
                plannerRules
        );
    }

    /** 解析可选系统模块元数据、角色和能力声明。 */
    private ModuleSystemMetadata parseSystemMetadata(JsonNode rawSystem) {
        if (rawSystem.isMissingNode() || rawSystem.isNull()) {
            return null;
        }
        if (!rawSystem.isObject()) {
            throw new IllegalStateException("模块 system 必须是对象");
        }
        String role = rawSystem.path("role").asText("");
        JsonNode protectedNode = rawSystem.get("protected");
        if (protectedNode == null || !protectedNode.isBoolean() || !protectedNode.asBoolean()) {
            throw new IllegalStateException("模块 system.protected 必须为 true");
        }
        JsonNode rawCapabilities = rawSystem.get("capabilities");
        if (rawCapabilities == null || !rawCapabilities.isArray()) {
            throw new IllegalStateException("模块 system.capabilities 必须是数组");
        }
        List<String> capabilities = new ArrayList<>();
        for (JsonNode capability : rawCapabilities) {
            if (!capability.isTextual() || capability.asText().isBlank()) {
                throw new IllegalStateException(
                        "模块 system.capabilities 只能包含非空文本"
                );
            }
            capabilities.add(capability.asText());
        }
        try {
            return new ModuleSystemMetadata(
                    role,
                    true,
                    capabilities
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * 解析 Action 契约，包括风险、参数和可选输出 schema。
     *
     * <p>AI 可调用状态只属于宿主动态治理，不进入模块 JAR 契约；
     * {@code outputSchema} 仅接受宿主支持的 JSON Schema 子集。</p>
     */
    private ActionContract parseAction(JsonNode node) {
        requireText(node, "id");
        requireText(node, "title");
        requireText(node, "description");
        requireText(node, "risk");
        rejectDeprecatedAiCallable(node, "动作");
        RiskLevel risk;
        try {
            risk = RiskLevel.valueOf(node.path("risk").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("动作风险等级非法：" + node.path("id").asText());
        }
        JsonNode rawConfirmationRequired = node.get("confirmationRequired");
        if (rawConfirmationRequired != null && !rawConfirmationRequired.isBoolean()) {
            throw new IllegalStateException("动作 confirmationRequired 必须是布尔值：" + node.path("id").asText());
        }
        JsonNode outputSchema = node.get("outputSchema");
        if (outputSchema != null && !outputSchema.isNull()) {
            try {
                OutputSchemaValidator.validateDefinition(outputSchema);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException(
                        "动作 " + node.path("id").asText() + " 的 outputSchema 非法：" + exception.getMessage(),
                        exception
                );
            }
        }
        List<ParameterContract> parameters = parseParameters(node.path("parameters"), "动作");
        return new ActionContract(
                node.path("id").asText(),
                node.path("title").asText(),
                node.path("description").asText(),
                risk,
                node.path("confirmationRequired").asBoolean(false),
                node.path("confirmationPhrase").asText(""),
                List.copyOf(parameters),
                outputSchema == null || outputSchema.isNull() ? null : outputSchema.deepCopy()
        );
    }

    /** 解析只读 Query 契约；AI 可调用状态只由宿主治理。 */
    private QueryContract parseQuery(JsonNode node) {
        requireText(node, "id");
        requireText(node, "title");
        requireText(node, "description");
        rejectDeprecatedAiCallable(node, "查询");
        JsonNode outputSchema = node.get("outputSchema");
        if (outputSchema != null && !outputSchema.isNull()) {
            if (!outputSchema.isObject()
                    || !"object".equals(outputSchema.path("type").asText())) {
                throw new IllegalStateException(
                        "查询 " + node.path("id").asText()
                                + " 的 outputSchema 根类型必须是 object"
                );
            }
            try {
                OutputSchemaValidator.validateDefinition(outputSchema);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException(
                        "查询 " + node.path("id").asText()
                                + " 的 outputSchema 非法：" + exception.getMessage(),
                        exception
                );
            }
        }
        return new QueryContract(
                node.path("id").asText(),
                node.path("title").asText(),
                node.path("description").asText(),
                parseParameters(node.path("parameters"), "查询"),
                outputSchema == null || outputSchema.isNull()
                        ? null
                        : outputSchema.deepCopy(),
                parseQueryDisplay(node.get("display"), node.path("id").asText())
        );
    }

    /** 解析模块声明的 Query 展示建议，并限制其只能描述已有字段。 */
    private QueryDisplayContract parseQueryDisplay(JsonNode node, String queryId) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("查询 " + queryId + " 的 display 必须是对象");
        }
        String prompt = requiredBoundedText(node, "prompt", 1000, "查询 " + queryId + " display");
        String fallbackLayout = requiredTextValue(
                node,
                "fallbackLayout",
                "查询 " + queryId + " display"
        );
        if (!Set.of("keyValue", "list", "table", "text", "timeline").contains(fallbackLayout)) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display.fallbackLayout 非法：" + fallbackLayout
            );
        }
        String rowPath = optionalBoundedText(
                node,
                "rowPath",
                200,
                "查询 " + queryId + " display"
        );
        if (rowPath != null && !rowPath.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display.rowPath 只能使用点分隔字段名"
            );
        }
        String emptyText = optionalBoundedText(
                node,
                "emptyText",
                200,
                "查询 " + queryId + " display"
        );
        String density = optionalTextValue(node, "density", "comfortable");
        if (!Set.of("compact", "comfortable").contains(density)) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display.density 非法：" + density
            );
        }
        String groupBy = optionalTextValue(node, "groupBy", "none");
        if (!Set.of("none", "date").contains(groupBy)) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display.groupBy 非法：" + groupBy
            );
        }
        JsonNode repeatedNode = node.get("hideRepeatedFields");
        if (repeatedNode != null && !repeatedNode.isBoolean()) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display.hideRepeatedFields 必须是布尔值"
            );
        }
        boolean hideRepeatedFields = repeatedNode != null && repeatedNode.asBoolean();
        List<QueryDisplayContract.Field> fields = parseDisplayFields(
                node.get("fields"),
                "查询 " + queryId + " display.fields"
        );
        List<QueryDisplayContract.Field> detailFields = parseDisplayFields(
                node.get("detailFields"),
                "查询 " + queryId + " display.detailFields"
        );
        List<String> emphasisFields = boundedTextArray(
                node.get("emphasisFields"),
                2,
                160,
                "查询 " + queryId + " display.emphasisFields"
        );
        Set<String> availableFields = new LinkedHashSet<>();
        fields.forEach(field -> availableFields.add(field.key()));
        detailFields.forEach(field -> availableFields.add(field.key()));
        for (String emphasis : emphasisFields) {
            if (!availableFields.contains(emphasis)) {
                throw new IllegalStateException(
                        "查询 " + queryId + " display.emphasisFields 引用了未展示字段"
                );
            }
        }
        boolean hasTimeRole = fields.stream().anyMatch(field -> "time".equals(field.role()));
        boolean hasPrimaryRole = fields.stream()
                .anyMatch(field -> "primary".equals(field.role()));
        if ("timeline".equals(fallbackLayout) && (!hasTimeRole || !hasPrimaryRole)) {
            throw new IllegalStateException(
                    "查询 " + queryId
                            + " display 的 timeline 布局需要 time 和 primary 字段"
            );
        }
        if ("date".equals(groupBy) && !hasTimeRole) {
            throw new IllegalStateException(
                    "查询 " + queryId + " display 的 date 分组需要 time 字段"
            );
        }
        return new QueryDisplayContract(
                prompt,
                fallbackLayout,
                rowPath,
                fields,
                emptyText,
                density,
                groupBy,
                hideRepeatedFields,
                detailFields,
                emphasisFields
        );
    }

    /** 解析模块 Query 主展示字段和详情字段。 */
    private List<QueryDisplayContract.Field> parseDisplayFields(
            JsonNode rawFields,
            String owner
    ) {
        if (rawFields == null) {
            return List.of();
        }
        if (!rawFields.isArray() || rawFields.size() > 12) {
            throw new IllegalStateException(owner + " 必须是最多 12 项的数组");
        }
        List<QueryDisplayContract.Field> fields = new ArrayList<>();
        rawFields.forEach(field -> {
            if (!field.isObject()) {
                throw new IllegalStateException(owner + " 项必须是对象");
            }
            String key = requiredBoundedText(field, "key", 160, owner);
            if (!key.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
                throw new IllegalStateException(owner + ".key 只能使用点分隔字段名");
            }
            String label = requiredBoundedText(field, "label", 80, owner);
            String format = requiredTextValue(field, "format", owner);
            if (!Set.of("text", "status", "date", "time", "datetime", "number", "boolean")
                    .contains(format)) {
                throw new IllegalStateException(owner + ".format 非法：" + format);
            }
            String role = optionalTextValue(field, "role", null);
            if (role != null
                    && !Set.of("time", "primary", "secondary", "status").contains(role)) {
                throw new IllegalStateException(owner + ".role 非法：" + role);
            }
            if ("time".equals(role)
                    && !Set.of("date", "time", "datetime").contains(format)) {
                throw new IllegalStateException(owner + ".time 角色需要日期或时间格式");
            }
            fields.add(new QueryDisplayContract.Field(
                    key,
                    label,
                    format,
                    role,
                    parseValueLabels(field.get("valueLabels"), owner + ".valueLabels")
            ));
        });
        return List.copyOf(fields);
    }

    /** 解析模块声明的展示值到本地化标签映射。 */
    private Map<String, String> parseValueLabels(JsonNode node, String owner) {
        if (node == null) {
            return Map.of();
        }
        if (!node.isObject() || node.size() > 64) {
            throw new IllegalStateException(owner + " 必须是最多 64 项的字符串映射");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            JsonNode value = entry.getValue();
            if (key.isEmpty() || key.length() > 80
                    || value == null || !value.isTextual()
                    || value.asText().isBlank() || value.asText().length() > 80) {
                throw new IllegalStateException(owner + " 包含非法标签");
            }
            labels.put(key, value.asText().trim());
        });
        return Map.copyOf(labels);
    }

    private List<String> boundedTextArray(
            JsonNode node,
            int maximumItems,
            int maximumLength,
            String owner
    ) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray() || node.size() > maximumItems) {
            throw new IllegalStateException(owner + " 必须是最多 " + maximumItems + " 项的数组");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalStateException(owner + " 项必须是文本");
            }
            String value = item.asText().trim();
            if (value.isEmpty() || value.length() > maximumLength) {
                throw new IllegalStateException(owner + " 包含空值或超长文本");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private String requiredTextValue(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(owner + "." + field + " 必须是非空文本");
        }
        return value.asText().trim();
    }

    private String optionalTextValue(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(field + " 必须是非空文本");
        }
        return value.asText().trim();
    }

    private String requiredBoundedText(
            JsonNode node,
            String field,
            int maximum,
            String owner
    ) {
        String value = requiredTextValue(node, field, owner);
        if (value.length() > maximum) {
            throw new IllegalStateException(
                    owner + "." + field + " 不能超过 " + maximum + " 个字符"
            );
        }
        return value;
    }

    private String optionalBoundedText(
            JsonNode node,
            String field,
            int maximum,
            String owner
    ) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException(owner + "." + field + " 必须是文本");
        }
        String text = value.asText().trim();
        if (text.length() > maximum) {
            throw new IllegalStateException(
                    owner + "." + field + " 不能超过 " + maximum + " 个字符"
            );
        }
        return text.isBlank() ? null : text;
    }

    private void rejectDeprecatedAiCallable(JsonNode node, String owner) {
        if (node.has("aiCallable")) {
            throw new IllegalStateException(
                    owner + " aiCallable 已废弃，AI 可调用状态只能由宿主动态治理："
                            + node.path("id").asText()
            );
        }
    }

    /** 解析本地计划规则的正则、参数捕获组和路径参数映射。 */
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

    /**
     * 解析参数数组；首版只支持 string、boolean 和 integer。
     */
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

    /**
     * 校验模块内的动作命名、风险确认和参数唯一性。
     *
     * <p>写和删除动作必须声明确认；执行期仍需经过宿主授权与执行核心，契约字段
     * 本身不能替代运行时担保。</p>
     */
    private void validateModule(ModuleContract module) {
        for (ActionContract action : module.actions()) {
            if (!action.id().contains(".")) {
                throw new IllegalStateException("动作 ID 应包含模块命名空间：" + action.id());
            }
            if (action.risk() != RiskLevel.READ_ONLY && !action.confirmationRequired()) {
                throw new IllegalStateException("写或删除动作必须要求确认：" + action.id());
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

    /** 校验 Query 的模块命名空间和参数唯一性。 */
    private void validateQuery(ModuleContract module, QueryContract query) {
        if (!query.id().contains(".")) {
            throw new IllegalStateException("查询 ID 应包含模块命名空间：" + query.id());
        }
        requireUniqueParameters(query.id(), query.parameters());
    }

    /**
     * 校验计划规则只引用本模块动作、有效捕获组和已声明的字符串路径参数。
     */
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

    /** 拒绝同一动作或查询中重复声明参数名。 */
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

    /** 失败清理时只关闭临时加载器，不覆盖正在传播的根因异常。 */
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

        /** 返回本次预检新增的模块契约。 */
        public ModuleContract module() {
            return module;
        }

        /** 提交候选快照；仅在预检结果未关闭且目录代次仍匹配时成功。 */
        public void commit() {
            owner.commitPrepared(this);
        }

        /** 将已提交快照恢复到预检前的状态，并关闭候选类加载器。 */
        public void rollback() {
            owner.rollbackPrepared(this);
        }

        /** 未提交就关闭时释放候选类加载器，不改变当前宿主快照。 */
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
