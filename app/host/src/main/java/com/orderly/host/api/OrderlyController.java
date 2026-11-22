package com.orderly.host.api;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleUiAsset;
import com.orderly.host.contract.ModuleEntry;
import com.orderly.host.ai.AiContracts;
import com.orderly.host.ai.AiInterpretService;
import com.orderly.host.ai.AiQueryService;
import com.orderly.host.ai.AiServiceClient;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.execution.ExecutionRecordStore;
import com.orderly.host.execution.ExecutionRecordSummary;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.modulecontrol.ModuleDistributionService;
import com.orderly.host.modulecontrol.ModuleFolderService;
import com.orderly.host.modulecontrol.ModuleInterfaceState;
import com.orderly.host.modulecontrol.ModuleLifecycleService;
import com.orderly.host.modulecontrol.ModulePreferences;
import com.orderly.host.modulecontrol.ModuleRefreshResult;
import com.orderly.host.modulecontrol.ModuleRefreshService;
import com.orderly.host.moduledata.ModuleDataService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.system.BuildSettingsService;
import com.orderly.host.system.HostShutdownService;
import com.orderly.host.system.SystemFeatureService;
import com.orderly.host.theme.ThemeAsset;
import com.orderly.host.theme.ThemeCatalog;
import com.orderly.host.theme.ThemeCatalogSnapshot;
import com.orderly.host.theme.ThemeDefinition;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 本地前端使用的受控 HTTP 接口。
 *
 * <p>控制器只做请求绑定、输入形状检查和用例服务转发；模块元数据、Action、
 * Query、参数和计划规则始终来自 {@link ContractCatalog}，不由本类维护业务定义。
 * 控制器再注入模块治理、AI 编排、执行记录、事件和主题服务；文件资源先由对应
 * 服务完成白名单和路径校验，控制器不自行解析或遍历工作区路径。HTTP 服务本身
 * 只监听本机回环地址。</p>
 */
@RestController
@RequestMapping("/api")
public class OrderlyController {

    private final ContractCatalog catalog;
    private final PlanService plans;
    private final ExecutionService execution;
    private final ExecutionJournal journal;
    private final WorkspaceRuntime runtime;
    private final ThemeCatalog themes;
    private final ModuleControlService moduleControl;
    private final HostShutdownService hostShutdown;
    private final BuildSettingsService buildSettings;
    private final ModuleLifecycleService moduleLifecycle;
    private final ModuleRefreshService moduleRefresh;
    private final ModuleDistributionService moduleDistribution;
    private final ModuleFolderService moduleFolder;
    private final ModuleDataService moduleData;
    private final SystemFeatureService features;
    private final AiActionService aiActions;
    private final AiServiceClient aiServiceClient;
    private final AiQueryService aiQueries;
    private final AiInterpretService aiInterpret;
    private final ExecutionRecordStore records;

    public OrderlyController(
            ContractCatalog catalog,
            PlanService plans,
            ExecutionService execution,
            ExecutionJournal journal,
            WorkspaceRuntime runtime,
            ThemeCatalog themes,
            ModuleControlService moduleControl,
            HostShutdownService hostShutdown,
            BuildSettingsService buildSettings,
            ModuleLifecycleService moduleLifecycle,
            ModuleRefreshService moduleRefresh,
            ModuleDistributionService moduleDistribution,
            ModuleFolderService moduleFolder,
            ModuleDataService moduleData,
            SystemFeatureService features,
            AiActionService aiActions,
            AiServiceClient aiServiceClient,
            AiQueryService aiQueries,
            AiInterpretService aiInterpret,
            ExecutionRecordStore records
    ) {
        this.catalog = catalog;
        this.plans = plans;
        this.execution = execution;
        this.journal = journal;
        this.runtime = runtime;
        this.themes = themes;
        this.moduleControl = moduleControl;
        this.hostShutdown = hostShutdown;
        this.buildSettings = buildSettings;
        this.moduleLifecycle = moduleLifecycle;
        this.moduleRefresh = moduleRefresh;
        this.moduleDistribution = moduleDistribution;
        this.moduleFolder = moduleFolder;
        this.moduleData = moduleData;
        this.features = features;
        this.aiActions = aiActions;
        this.aiServiceClient = aiServiceClient;
        this.aiQueries = aiQueries;
        this.aiInterpret = aiInterpret;
        this.records = records;
    }

    /** 只返回 Java 宿主自身存活状态，不探测可选 AI 服务。 */
    @GetMapping("/health/live")
    public Map<String, Object> liveness() {
        return Map.of(
                "status", "UP",
                "javaVersion", Runtime.version().feature()
        );
    }

    /** 返回应用、AI 服务和工作区的最小健康摘要。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "planner", "LOCAL_RULES",
                "aiConfigured", aiServiceClient.configured(),
                "aiService", aiServiceClient.health(),
                "workspace", runtime.workspaceLabel(),
                "javaVersion", Runtime.version().feature()
        );
    }

    /** 读取旧版计划与独立 AI 计划的功能开关。 */
    @GetMapping("/system/features")
    public Map<String, Object> systemFeatures() {
        return features.features();
    }

    /** 返回后台模块构建并发设置。 */
    @GetMapping("/system/build-settings")
    public Map<String, Object> buildSettings() {
        return buildSettings.snapshot();
    }

    /** 更新后台模块构建并发设置；请求必须带有明确的本地动作标记。 */
    @PutMapping("/system/build-settings")
    public Map<String, Object> updateBuildSettings(
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action,
            @RequestBody BuildConcurrencyRequest request
    ) {
        if (!"update-build-settings".equals(action)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BUILD_SETTINGS_REQUEST",
                    "构建设置请求缺少有效的 X-Orderly-Action 请求头"
            );
        }
        if (request == null || request.maxParallel() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BUILD_CONCURRENCY",
                    "maxParallel 必须是 " + BuildSettingsService.MIN_MAX_PARALLEL
                            + "-" + BuildSettingsService.MAX_MAX_PARALLEL + " 的整数"
            );
        }
        return buildSettings.setMaxParallel(request.maxParallel());
    }

    /**
     * 请求安全退出本地宿主。自定义请求头使浏览器跨站表单无法直接触发该操作。
     */
    @PostMapping("/system/shutdown")
    public ResponseEntity<HostShutdownService.ShutdownAccepted> shutdownHost(
            @RequestHeader(name = "X-Orderly-Action", defaultValue = "") String action
    ) {
        if (!"shutdown".equals(action)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SHUTDOWN_REQUEST",
                    "退出请求缺少有效的 X-Orderly-Action 请求头"
            );
        }
        return ResponseEntity.accepted().body(hostShutdown.requestShutdown());
    }

    /** 更新受控的旧版计划审查功能开关。 */
    @PutMapping("/system/features/planning")
    public Map<String, Object> updatePlanningFeature(
            @RequestBody PlanningFeatureRequest request
    ) {
        if (request == null || request.enabled() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "enabled 必须是布尔值"
            );
        }
        return features.setPlanningEnabled(request.enabled());
    }

    /** 更新独立 AI 计划功能开关，与旧版计划开关互不复用。 */
    @PutMapping("/system/features/ai-plan")
    public Map<String, Object> updateAiPlanFeature(
            @RequestBody PlanningFeatureRequest request
    ) {
        if (request == null || request.enabled() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "enabled 必须是布尔值"
            );
        }
        return features.setAiPlanEnabled(request.enabled());
    }

    /** 返回带运行态启用标志的业务模块目录。 */
    @GetMapping("/modules")
    public List<ModuleEntry> modules() {
        return moduleControl.businessModules();
    }

    /** 返回后台模块构建状态；源码描述只用于展示，不构成可执行契约。 */
    @GetMapping("/system/module-builds")
    public ModuleLifecycleService.Snapshot moduleBuilds() {
        return moduleLifecycle.snapshot();
    }

    /** 请求后台构建脚本优先处理一个已发现的业务模块源码。 */
    @PostMapping("/system/module-builds/prioritize")
    public ModuleLifecycleService.Snapshot prioritizeModuleBuild(
            @RequestBody ModuleBuildPriorityRequest request
    ) {
        if (request == null || request.sourceName() == null || request.sourceName().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "sourceName 不能为空"
            );
        }
        return moduleLifecycle.prioritize(request.sourceName());
    }

    /** 返回不可作为普通业务模块治理的系统模块目录。 */
    @GetMapping("/system-modules")
    public List<ModuleEntry> systemModules() {
        return moduleControl.systemModules();
    }

    /** 返回主题快照、问题列表和安全回退状态。 */
    @GetMapping("/themes")
    public ThemeCatalogSnapshot themes() {
        return themes.snapshot();
    }

    /** 读取一个已加载主题的完整清单。 */
    @GetMapping("/themes/{id}")
    public ThemeDefinition theme(@PathVariable String id) {
        try {
            return themes.require(id);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "THEME_NOT_FOUND", exception.getMessage());
        }
    }

    /** 返回主题清单登记且通过目录边界检查的静态资源。 */
    @GetMapping("/themes/{id}/assets/{*asset}")
    public ResponseEntity<byte[]> themeAsset(
            @PathVariable String id,
            @PathVariable("asset") String asset
    ) {
        ThemeAsset resource = themes.readAsset(id, asset).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "THEME_ASSET_NOT_FOUND",
                        "主题资源不存在或未登记：" + id + "/" + asset
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.contentType()))
                .header("Cache-Control", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource.content());
    }

    /**
     * 执行模块自有的只读 Query；参数和启用状态由 AI/契约服务统一校验。
     */
    @GetMapping("/modules/{moduleId}/query/{queryId}")
    public Map<String, Object> queryModule(
            @PathVariable String moduleId,
            @PathVariable String queryId,
            @RequestParam Map<String, String> parameters
    ) {
        return aiQueries.executeHttpQuery(moduleId, queryId, parameters);
    }

    /** 从模块 JAR 白名单读取模块 UI 清单、脚本或样式。 */
    @GetMapping("/modules/{moduleId}/ui/{asset}")
    public ResponseEntity<byte[]> moduleUi(
            @PathVariable String moduleId,
            @PathVariable String asset
    ) {
        ModuleUiAsset resource = catalog.readUiAsset(moduleId, asset).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_UI_NOT_FOUND",
                        "模块 UI 资源不存在：" + moduleId + "/" + asset
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.contentType()))
                .header("Cache-Control", "no-cache")
                .body(resource.content());
    }

    /** 提供模块工作区中受宿主路径边界保护的只读数据资源。 */
    @GetMapping("/modules/{moduleId}/data/{*asset}")
    public ResponseEntity<Resource> moduleData(
            @PathVariable String moduleId,
            @PathVariable("asset") String asset
    ) {
        ModuleDataService.ModuleDataAsset resource = moduleData.open(moduleId, asset);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.contentType()))
                .contentLength(resource.size())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(resource.file()));
    }

    /**
     * 先暂存上传文件，再通过统一 Action 执行核心运行模块上传动作。
     *
     * <p>动作成功或抛出运行时业务异常时会清理暂存数据；实际写入、确认、快照、
     * 审计和回滚仍由宿主执行核心负责。</p>
     */
    @PostMapping(
            value = "/modules/{moduleId}/actions/{actionId}/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ExecutionRecord executeModuleActionWithUpload(
            @PathVariable String moduleId,
            @PathVariable String actionId,
            @RequestPart("request") ModuleActionRequest request,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        Object rawUploadPath = request.arguments().get("uploadPath");
        String uploadPath = rawUploadPath == null ? "" : rawUploadPath.toString();
        if (uploadPath.isBlank() || file == null || file.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MODULE_UPLOAD",
                    "上传动作必须包含 uploadPath 与非空文件"
            );
        }
        moduleData.stage(
                moduleId,
                uploadPath,
                file.getContentType(),
                file.getBytes()
        );
        try {
            ExecutionRecord record = aiActions.executeModuleControl(
                    moduleId,
                    actionId,
                    request.arguments(),
                    request.confirmed()
            );
            moduleData.cleanup(moduleId, uploadPath, record.executionId(), "upload-cleanup");
            return record;
        } catch (RuntimeException exception) {
            moduleData.cleanup(moduleId, uploadPath, "module-upload-failed", "upload-cleanup");
            throw exception;
        }
    }

    /** 通过统一 Action 执行核心运行模块自有动作，来源标记为模块控制。 */
    @PostMapping("/modules/{moduleId}/actions/{actionId}")
    public ExecutionRecord executeModuleAction(
            @PathVariable String moduleId,
            @PathVariable String actionId,
            @RequestBody ModuleActionRequest request
    ) {
        if (request == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "模块动作请求体不能为空"
            );
        }
        return aiActions.executeModuleControl(
                moduleId,
                actionId,
                request.arguments(),
                request.confirmed()
        );
    }

    /** 通过旧版计划入口创建本地规则计划。 */
    @PostMapping("/plans")
    public PlanView createPlan(@RequestBody GoalRequest request) {
        return plans.create(request.goal());
    }

    /** 确认旧版计划；写入/删除确认词策略由执行服务统一校验。 */
    @PostMapping("/plans/{planId}/confirm")
    public ExecutionRunResult confirm(
            @PathVariable String planId,
            @RequestBody ConfirmRequest request
    ) {
        return execution.confirm(planId, request.confirmed(), request.confirmation());
    }

    /** 回滚一个旧版计划执行记录。 */
    @PostMapping("/plans/{planId}/rollback")
    public ExecutionRunResult rollback(@PathVariable String planId) {
        return execution.rollback(planId);
    }

    /** 通过受确认标记约束的统一 Action 核心执行总体 AI 动作数组。 */
    @PostMapping("/ai/actions/execute")
    public ExecutionRecord executeAiActions(@RequestBody AiActionsRequest request) {
        if (request == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "请求体不能为空"
            );
        }
        return aiActions.execute(request.confirmed(), request.actions());
    }

    /** 读取 Python 进程中的 AI 设置视图；响应不包含 API Key。 */
    @GetMapping("/ai/settings")
    public AiContracts.SettingsView aiSettings() {
        return aiServiceClient.settings();
    }

    /**
     * 把 AI 设置转发给 Python 进程；API Key 仅作为写入字段短暂经过本机 IPC。
     */
    @PutMapping("/ai/settings")
    public AiContracts.SettingsView updateAiSettings(
            @RequestBody AiContracts.SettingsRequest request
    ) {
        return aiServiceClient.updateSettings(request);
    }

    /** 清除 Python 进程内的模型配置，不在 Java 侧保留凭据。 */
    @DeleteMapping("/ai/settings")
    public AiContracts.SettingsView clearAiSettings() {
        return aiServiceClient.clearSettings();
    }

    /** 触发 Python 服务使用内存配置执行连通性测试。 */
    @PostMapping("/ai/settings/test")
    public AiContracts.ConnectionTest testAiSettings() {
        return aiServiceClient.testSettings();
    }

    /** 处理一个自然语言回合；最终动作仍由宿主校验和担保。 */
    @PostMapping("/ai/turns")
    public AiContracts.TurnResponse aiTurn(@RequestBody AiContracts.TurnRequest request) {
        moduleLifecycle.requireReadyForAi();
        return aiInterpret.interpret(request);
    }

    /** 确认 Java 宿主持有的独立 AI 计划。 */
    @PostMapping("/ai/plans/{planId}/confirm")
    public ExecutionRecord confirmAiPlan(@PathVariable String planId) {
        return aiInterpret.confirmPlan(planId);
    }

    /** 列出持久化执行记录摘要。 */
    @GetMapping("/executions")
    public List<ExecutionRecordSummary> executions() {
        return records.list();
    }

    /** 读取一条完整执行记录。 */
    @GetMapping("/executions/{executionId}")
    public ExecutionRecord execution(@PathVariable String executionId) {
        return records.require(executionId);
    }

    /** 按持久化快照回滚一条可回滚执行记录。 */
    @PostMapping("/executions/{executionId}/rollback")
    public ExecutionRecord rollbackExecution(@PathVariable String executionId) {
        return execution.rollbackExecution(executionId);
    }

    /** 仅在旧版计划功能启用时列出运行记录。 */
    @GetMapping("/runs")
    public List<PlanView> runs() {
        features.requirePlanningEnabled();
        return plans.list();
    }

    /** 返回事件时间线的当前快照。 */
    @GetMapping("/events")
    public List<ExecutionEvent> events() {
        return journal.snapshot();
    }

    /** 订阅持续事件流，供前端显示执行和治理状态变化。 */
    @GetMapping(path = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return journal.subscribe();
    }

    /** 启用或停用业务模块；系统模块和运行中占用由治理服务拒绝。 */
    @PatchMapping("/modules/{moduleId}/activation")
    public ModuleEntry activation(
            @PathVariable String moduleId,
            @RequestBody ActivationRequest request
    ) {
        if (request == null || request.enabled() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "activation.enabled 必须是布尔值"
            );
        }
        return moduleControl.setActivation(moduleId, request.enabled());
    }

    /** 读取模块启用态、两套排序和接口动态开关等治理状态。 */
    @GetMapping("/system/module-preferences")
    public ModulePreferences modulePreferences() {
        return moduleControl.preferences();
    }

    /** 读取业务 Action/Query 的动态 AI 可调用状态。 */
    @GetMapping("/system/module-interfaces")
    public ModuleInterfaceState moduleInterfaces() {
        return moduleControl.interfaceState();
    }

    /**
     * 更新治理层的 AI 可调用开关；系统模块和未声明接口不可修改。
     */
    @PatchMapping("/modules/{moduleId}/interfaces/{kind}/{interfaceId}/ai-callable")
    public ModuleInterfaceState updateInterfaceAiCallable(
            @PathVariable String moduleId,
            @PathVariable String kind,
            @PathVariable String interfaceId,
            @RequestBody InterfaceAiCallableRequest request
    ) {
        if (request == null || request.aiCallable() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "aiCallable 必须是布尔值"
            );
        }
        return moduleControl.setInterfaceAiCallable(
                moduleId,
                kind,
                interfaceId,
                request.aiCallable()
        );
    }

    /** 持久化独立于总览卡片顺序的侧边栏模块顺序。 */
    @PutMapping("/system/module-preferences/sidebar-order")
    public ModulePreferences updateSidebarOrder(@RequestBody ModuleOrderRequest request) {
        return moduleControl.updateSidebarOrder(requireModuleIds(request));
    }

    /** 持久化模块管理界面的顺序。 */
    @PutMapping("/system/module-preferences/management-order")
    public ModulePreferences updateManagementOrder(@RequestBody ModuleOrderRequest request) {
        return moduleControl.updateManagementOrder(requireModuleIds(request));
    }

    /** 按模块 UI 清单校验并合并声明过的设置字段。 */
    @PutMapping("/modules/{moduleId}/settings")
    public Map<String, Object> updateModuleSettings(
            @PathVariable String moduleId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode settings
    ) {
        return moduleControl.updateSettings(moduleId, settings);
    }

    /** 将模块设置恢复为 UI 清单声明的默认值。 */
    @PostMapping("/modules/{moduleId}/settings/reset")
    public Map<String, Object> resetModuleSettings(@PathVariable String moduleId) {
        return moduleControl.resetSettings(moduleId);
    }

    /** 显式追加已构建的新业务模块；不替换或卸载当前已加载模块。 */
    @PostMapping("/system/modules/refresh")
    public ModuleRefreshResult refreshModules() {
        return moduleRefresh.refresh();
    }

    /** 返回固定 GitHub 分发分支中经过宿主校验的业务模块目录。 */
    @GetMapping("/system/module-catalog")
    public ModuleDistributionService.CatalogView moduleCatalog() {
        return moduleDistribution.catalog();
    }

    /** 创建模块下载与后台构建任务；请求必须声明本机安装动作。 */
    @PostMapping("/system/module-catalog/install")
    public ResponseEntity<ModuleDistributionService.InstallAccepted> installModule(
            @RequestHeader(value = "X-Orderly-Action", required = false) String action,
            @RequestBody ModuleInstallRequest request
    ) {
        if (!"install-module".equals(action)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTION_HEADER",
                    "安装模块必须声明 X-Orderly-Action: install-module"
            );
        }
        if (request == null || request.moduleId() == null || request.moduleId().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "moduleId 不能为空"
            );
        }
        return ResponseEntity.accepted().body(moduleDistribution.install(request.moduleId()));
    }

    /** 读取模块下载、校验、构建和追加加载的当前状态。 */
    @GetMapping("/system/module-catalog/install/{jobId}")
    public ModuleDistributionService.InstallStatus moduleInstallStatus(@PathVariable String jobId) {
        return moduleDistribution.status(jobId);
    }

    /**
     * 打开配置的模块源码目录。
     *
     * <p>请求必须携带固定动作头和本机用户意图；服务不接受客户端路径，且只在
     * Windows 上执行。</p>
     */
    @PostMapping("/system/module-folder/open")
    public Map<String, Boolean> openModuleFolder(
            @RequestHeader(value = "X-Orderly-Action", required = false) String action
    ) {
        if (!"open-module-folder".equals(action)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTION_HEADER",
                    "打开模块目录必须声明 X-Orderly-Action: open-module-folder"
            );
        }
        return moduleFolder.open();
    }

    /** 旧版计划创建的入站目标。 */
    public record GoalRequest(String goal) {
    }

    /** 计划确认参数。 */
    public record ConfirmRequest(boolean confirmed, String confirmation) {
        public ConfirmRequest {
            confirmation = confirmation == null ? "" : confirmation;
        }
    }

    /** 业务模块启停请求。 */
    public record ActivationRequest(Boolean enabled) {
    }

    /** 单个 Action/Query 的动态 AI 可调用开关请求。 */
    public record InterfaceAiCallableRequest(Boolean aiCallable) {
    }

    /** 侧边栏或管理视图的模块 ID 顺序请求。 */
    public record ModuleOrderRequest(List<String> moduleIds) {
    }

    /** 系统功能开关请求。 */
    public record PlanningFeatureRequest(Boolean enabled) {
    }

    /** 后台模块构建并发设置请求。 */
    public record BuildConcurrencyRequest(Integer maxParallel) {
    }

    /** 后台模块构建优先请求。 */
    public record ModuleBuildPriorityRequest(String sourceName) {
    }

    /** 固定分发目录中的模块安装请求；包地址不接受客户端输入。 */
    public record ModuleInstallRequest(String moduleId) {
    }

    /** 模块控制动作请求，包含用户确认标志和动作参数。 */
    public record ModuleActionRequest(boolean confirmed, Map<String, Object> arguments) {
        public ModuleActionRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 总体 AI Action 数组直执请求。 */
    public record AiActionsRequest(boolean confirmed, List<AiActionService.ActionCall> actions) {
    }

    /** 校验排序请求确实提供 moduleIds 数组，具体 ID 由治理服务继续检查。 */
    private List<String> requireModuleIds(ModuleOrderRequest request) {
        if (request == null || request.moduleIds() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "moduleIds 必须是字符串数组"
            );
        }
        return request.moduleIds();
    }

}
