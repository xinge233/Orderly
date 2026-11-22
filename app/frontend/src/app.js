/**
 * 框架级前端外壳。
 *
 * 职责：维护应用导航、总览、历史、设置弹窗与模块挂载点，并把宿主 API 的契约数据
 * 投射为统一界面；具体业务视图只通过模块 UI 清单和挂载函数接入。
 *
 * 状态来源：可执行事实与治理状态来自本地 Java 宿主；主题选择、动效档位和总览卡片
 * 顺序来自 SettingsStore。前端不保存第二套模块或主题清单，也不直接判定授权。
 *
 * 模块隔离：单个模块的清单、样式或脚本失败只降级当前视图；框架不得为具体业务模块
 * 增加分支，模块之间也不得借此共享内部状态。
 */
import { ApiError, OrderlyApi } from "./api.js";
import { executionCompletedModules, mountAiDialog } from "./ai-dialog.js";
import {
    AI_MODES,
    normalizeAiSettings,
    validateAiSettingsDraft
} from "./ai-settings.js";
import { AI_ICON_OPTIONS, hydrateIcons, icon } from "./icons.js";
import {
    EVENT_ORIGIN,
    EVENT_ORIGIN_LABEL,
    RISK_LABEL,
    flattenText,
    formatDate,
    inferEventOrigin,
    mergeEvents,
    normalizeEvent,
    normalizeExecutionRecord,
    normalizePlan,
    requiresDeletePhrase,
    statusLabel,
    unwrapList,
    validateConfirmation
} from "./models.js";
import {
    createModuleMountContext,
    hasSystemAdminPrivilege,
    isBusinessModule,
    isModuleVisible,
    moveModuleOrder,
    moveVisibleModuleOrder,
    moduleBuildStatusLabel,
    normalizeModuleBuildSnapshot,
    normalizeModuleCatalogs,
    normalizeModulePreferences,
    normalizeModuleUi,
    pendingModuleBuilds,
    readCount,
    reconcileModulePreferences,
    reorderBefore,
    reorderRelative,
    resolveModuleUiCatalog,
    sortModules
} from "./modules.js";
import {
    MOTION_OPTIONS,
    normalizeAiPlanFeature,
    normalizeBuildSettings,
    normalizeThemeCatalog,
    normalizePlanningFeature,
    reconcileAppliedTheme,
    reconcileSettingsForCatalog,
    SettingsStore,
    ThemeManager
} from "./settings.js";

const api = new OrderlyApi();
const settingsStore = new SettingsStore();
const themeManager = new ThemeManager();
const NAV_DRAG_HOLD_MS = 320;
const NAV_DRAG_MOVE_TOLERANCE = 7;
const MOBILE_NAV_QUERY = "(max-width: 820px)";
const DESKTOP_SIDEBAR_QUERY = "(min-width: 821px)";
const SIDEBAR_POINTER_QUERY = "(min-width: 821px) and (hover: hover) and (pointer: fine)";
const SIDEBAR_EXPAND_DELAY_MS = 40;
const SIDEBAR_COLLAPSE_DELAY_MS = 120;

const NAV_SECTIONS = Object.freeze([
    {
        id: "workspace",
        label: "工作空间",
        items: [
            { id: "overview", label: "总览", caption: "计划与执行", icon: "home" }
        ]
    },
    {
        id: "activity",
        label: "活动",
        items: [
            { id: "history", label: "历史记录", caption: "事件与运行摘要", icon: "history" }
        ]
    }
]);

const HISTORY_VIEWS = new Set(["timeline", "summary"]);
const ROLLBACK_ORIGINS = new Set([
    EVENT_ORIGIN.PLAN,
    EVENT_ORIGIN.AI_DIRECT,
    EVENT_ORIGIN.AI_PLAN
]);

function normalizeInterfaceFlagMap(input) {
    if (!input || typeof input !== "object" || Array.isArray(input)) {
        return {};
    }
    return Object.fromEntries(Object.entries(input).flatMap(([moduleId, values]) => {
        if (!/^[a-z][a-z0-9_-]{0,63}$/.test(moduleId)
            || !values
            || typeof values !== "object"
            || Array.isArray(values)) {
            return [];
        }
        const flags = Object.fromEntries(Object.entries(values)
            .filter(([, enabled]) => typeof enabled === "boolean"));
        return [[moduleId, flags]];
    }));
}

function normalizeModuleInterfaceState(input) {
    const source = input && typeof input === "object" && !Array.isArray(input) ? input : {};
    return {
        actionAiCallableEnabled: normalizeInterfaceFlagMap(source.actionAiCallableEnabled),
        queryAiCallableEnabled: normalizeInterfaceFlagMap(source.queryAiCallableEnabled),
        inUseActions: Array.isArray(source.inUseActions)
            ? [...new Set(source.inUseActions.filter((key) => typeof key === "string" && key))]
            : []
    };
}

/**
 * 框架共享视图状态。这里保存的是宿主快照、界面派生数据和临时交互状态；只有
 * SettingsStore 持有的外观项与卡片顺序会进入浏览器本地存储。
 */
const state = {
    activeView: "overview",
    health: null,
    planningEnabled: false,
    planningFeatureLoading: true,
    planningFeatureSaving: false,
    aiPlanEnabled: true,
    aiPlanFeatureLoading: true,
    aiPlanFeatureSaving: false,
    buildSettings: normalizeBuildSettings({}, 2),
    buildSettingsLoading: true,
    buildSettingsSaving: false,
    buildSettingsError: "",
    aiSettings: normalizeAiSettings({}),
    aiSettingsLoading: true,
    aiSettingsSaving: false,
    aiSettingsTesting: false,
    aiSettingsError: "",
    shutdownRequested: false,
    modules: [],
    moduleBuilds: normalizeModuleBuildSnapshot({}),
    moduleBuildLoading: true,
    modulePreferences: normalizeModulePreferences({}),
    moduleInterfaceState: normalizeModuleInterfaceState({}),
    moduleCatalogIssues: [],
    moduleRefreshIssues: [],
    themes: [],
    themeIssues: [],
    builtInFallbackActive: true,
    plan: null,
    planBusy: false,
    executing: false,
    confirmed: false,
    deletePhrase: "",
    currentRun: null,
    events: [],
    historyView: "timeline",
    historyRuns: [],
    historySource: "",
    historyIssues: [],
    rollbackFailures: {},
    rollbackBusyId: "",
    settingsSection: "appearance",
    moduleUi: {},
    moduleErrors: {},
    moduleCounts: {},
    dragModuleId: null,
    moduleAssetVersion: String(Date.now())
};

const systemAdminApi = createSystemAdminApi();
// 这些句柄跨越异步事件与动画帧，统一留在模块作用域，便于切换视口或离开页面时清理。
const frameworkEventListeners = new Set();
let eventStream = null;
let eventRenderQueued = false;
let navigationDrag = null;
let navigationHoldTimer = null;
let suppressNavigationClickUntil = 0;
let sidebarOrderRequestGeneration = 0;
let sidebarCountFrame = null;
let sidebarCountTrackingFrame = null;
let sidebarCountTrackingHandler = null;
let sidebarCountRefreshTimer = null;
let sidebarCountTransitionHandler = null;
let moduleBuildPollTimer = null;
let moduleBuildReloadInFlight = false;
let aiDialog = null;
const desktopSidebar = {
    active: false,
    expanded: false,
    pointerInside: false,
    timer: null,
    attentionTimer: null
};

/**
 * 创建简单元素。
 *
 * @param {string} tag 标签
 * @param {string} className 类名
 * @param {string} text 文本
 * @returns {HTMLElement} 元素
 */
function element(tag, className = "", text = "") {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text) {
        node.textContent = text;
    }
    return node;
}

function emitFrameworkEvent(event) {
    frameworkEventListeners.forEach((listener) => {
        try {
            listener(event);
        } catch {
            // 单个模块的事件监听失败不能阻断框架事件流。
        }
    });
}

function subscribeFrameworkEvents(listener) {
    if (typeof listener !== "function") {
        throw new TypeError("模块事件监听器必须是函数");
    }
    frameworkEventListeners.add(listener);
    return () => frameworkEventListeners.delete(listener);
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

/**
 * 设置一个节点文本。
 *
 * @param {ParentNode} root 根
 * @param {string} selector 选择器
 * @param {unknown} value 文本
 */
function setText(root, selector, value) {
    const node = root.querySelector(selector);
    if (node) {
        node.textContent = flattenText(value);
    }
}

/**
 * 展示短暂状态提示。
 *
 * @param {string} message 消息
 * @param {{error?: boolean}} options 配置
 */
function toast(message, { error = false } = {}) {
    const node = document.querySelector("#toast");
    if (!node) {
        return;
    }
    node.dataset.motionScope = "feedback";
    node.dataset.motionState = error ? "error" : "success";
    node.dataset.motionPhase = "active";
    node.textContent = message;
    node.classList.toggle("is-error", error);
    node.classList.add("is-visible");
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => {
        node.classList.remove("is-visible");
        node.dataset.motionState = "idle";
        node.dataset.motionPhase = "idle";
    }, 3200);
}

/**
 * 规范化 API 错误。
 *
 * @param {unknown} error 错误
 * @returns {string} 消息
 */
function errorMessage(error) {
    if (error instanceof ApiError) {
        return error.message;
    }
    return error?.message || "发生未知错误";
}

/**
 * 应用由宿主持有的计划功能状态。
 *
 * @param {boolean} enabled 是否启用
 * @param {{render?: boolean, resetPlan?: boolean}} options 渲染配置
 */
function applyPlanningFeatureState(enabled, { render = true, resetPlan = true } = {}) {
    const next = enabled === true;
    state.planningEnabled = next;
    document.documentElement.dataset.planning = next ? "enabled" : "disabled";
    if (!next && resetPlan) {
        state.plan = null;
        state.planBusy = false;
        state.executing = false;
        state.confirmed = false;
        state.deletePhrase = "";
        state.currentRun = null;
    }
    if (render) {
        if (state.activeView === "overview"
            && !document.querySelector(".loading-panel")) {
            renderOverview();
        }
    }
}

/**
 * 更新设置弹窗内的状态反馈。
 *
 * @param {string} message 消息
 * @param {{error?: boolean}} options 配置
 */
function setSettingsStatus(message, { error = false } = {}) {
    const status = document.querySelector("#settings-status");
    if (!status) {
        return;
    }
    status.textContent = message;
    status.classList.toggle("is-error", error);
}

/**
 * 启动框架外壳。
 *
 * 先建立独立 AI Dock 与设置入口，再并行读取宿主目录；有依赖关系的设置只能在这些
 * 快照归一化之后应用。随后连接事件流并加载默认视图。
 *
 * 副作用：注册全局事件、读取宿主 API、写入模块作用域状态、驱动首屏渲染，并建立
 * 页面卸载时的流清理。单个目录请求失败会保留安全降级状态，不阻断其他区域启动。
 */
async function bootstrap() {
    applyPlanningFeatureState(false, { render: false, resetPlan: false });
    hydrateIcons();
    bindGlobalControls();
    aiDialog = mountAiDialog({
        dock: document.querySelector("#ai-dock"),
        callLayer: document.querySelector("#ai-call-layer"),
        getModules: () => state.modules.filter(isModuleVisible),
        getAiSettings: () => state.aiSettings,
        saveAiSettings: (settings) => api.saveAiSettings(settings),
        clearAiSettings: () => api.clearAiSettings(),
        testAiSettings: () => api.testAiSettings(),
        interpretGoal: (payload) => api.interpretAiTurn(payload),
        confirmAiPlan: (planId) => api.confirmAiPlan(planId),
        beforeAiTurn: waitForModulesBeforeAiTurn,
        onAiSettingsChanged: (settings) => {
            state.aiSettings = normalizeAiSettings(settings);
            state.aiSettingsError = "";
        },
        onExecutionFinished: refreshAfterAiExecution,
        onNotice: (message, options) => toast(message, options)
    });
    bindSettingsDialog();

    // 目录、偏好、主题、功能开关和 AI 状态彼此独立；使用 allSettled 隔离失败来源，
    // 避免一个可选能力不可用拖垮整个本地工作台。
    const [
        moduleResult,
        systemModuleResult,
        preferenceResult,
        themeResult,
        featureResult,
        buildSettingsResult,
        aiSettingsResult,
        moduleBuildResult
    ] = await Promise.allSettled([
        api.getModules(),
        api.getSystemModules(),
        api.getModulePreferences(),
        api.getThemes(),
        api.getSystemFeatures(),
        api.getBuildSettings(),
        api.getAiSettings(),
        api.getModuleBuilds()
    ]);
    const catalogs = normalizeModuleCatalogs(
        moduleResult.status === "fulfilled" ? moduleResult.value : [],
        systemModuleResult.status === "fulfilled" ? systemModuleResult.value : []
    );
    state.modules = catalogs.modules;
    state.moduleCatalogIssues = [
        ...catalogIssues(moduleResult.status === "fulfilled" ? moduleResult.value : {}),
        ...catalogIssues(systemModuleResult.status === "fulfilled" ? systemModuleResult.value : {})
    ];
    state.modulePreferences = reconcileModulePreferences(
        state.modules,
        preferenceResult.status === "fulfilled" ? preferenceResult.value : {}
    );
    state.moduleBuilds = normalizeModuleBuildSnapshot(
        moduleBuildResult.status === "fulfilled" ? moduleBuildResult.value : {}
    );
    state.moduleBuildLoading = false;
    const themeCatalog = normalizeThemeCatalog(
        themeResult.status === "fulfilled" ? themeResult.value : {}
    );
    state.themes = themeCatalog.items;
    state.themeIssues = themeCatalog.issues;
    state.builtInFallbackActive = themeCatalog.builtInFallbackActive;
    themeManager.setCatalog(themeCatalog);
    state.planningFeatureLoading = false;
    state.aiPlanFeatureLoading = false;
    state.buildSettingsLoading = false;
    state.aiSettingsLoading = false;
    if (buildSettingsResult.status === "fulfilled") {
        state.buildSettings = normalizeBuildSettings(buildSettingsResult.value, 2);
        state.buildSettingsError = "";
    } else {
        state.buildSettings = normalizeBuildSettings({}, 2);
        state.buildSettingsError = errorMessage(buildSettingsResult.reason);
    }
    if (aiSettingsResult.status === "fulfilled") {
        state.aiSettings = normalizeAiSettings(aiSettingsResult.value);
        state.aiSettingsError = "";
    } else {
        state.aiSettingsError = errorMessage(aiSettingsResult.reason);
    }
    applyPlanningFeatureState(
        normalizePlanningFeature(
            featureResult.status === "fulfilled" ? featureResult.value : {},
            false
        ),
        { render: false }
    );
    state.aiPlanEnabled = normalizeAiPlanFeature(
        featureResult.status === "fulfilled" ? featureResult.value : {},
        true
    );

    await loadModuleUis();
    const reconciled = reconcileSettingsForCatalog(
        settingsStore.getSnapshot(),
        state.modules.filter(isBusinessModule),
        state.themes
    );
    settingsStore.update(reconciled);
    settingsStore.subscribe(applySettings);
    applySettings(settingsStore.getSnapshot());
    renderSettings();
    if (document.querySelector("#settings-dialog")?.open) {
        renderSettings();
    }

    connectEventStream();
    try {
        const [health, events] = await Promise.all([
            api.health(),
            api.getEvents().catch(() => [])
        ]);
        state.health = health && typeof health === "object" ? health : {};
        state.events = mergeEvents([], unwrapList(events, ["events"]));
        updateHealth();
    } catch (error) {
        updateHealth(error);
        toast(errorMessage(error), { error: true });
    }

    if (moduleResult.status === "rejected") {
        toast(`模块目录读取失败：${errorMessage(moduleResult.reason)}`, { error: true });
    }
    if (systemModuleResult.status === "rejected") {
        toast(`系统模块读取失败：${errorMessage(systemModuleResult.reason)}`, { error: true });
    }
    if (preferenceResult.status === "rejected") {
        toast(`模块偏好读取失败：${errorMessage(preferenceResult.reason)}`, { error: true });
    }
    if (themeResult.status === "rejected") {
        toast(`主题目录读取失败：${errorMessage(themeResult.reason)}`, { error: true });
    }
    if (featureResult.status === "rejected") {
        toast(`计划功能状态读取失败，已按关闭处理：${errorMessage(featureResult.reason)}`, {
            error: true
        });
    }
    if (buildSettingsResult.status === "rejected") {
        toast(`模块构建设置读取失败，后台将使用两路并发：${errorMessage(buildSettingsResult.reason)}`, {
            error: true
        });
    }
    if (moduleBuildResult.status === "rejected") {
        toast(`模块构建状态读取失败：${errorMessage(moduleBuildResult.reason)}`, {
            error: true
        });
    }

    void navigate("overview");
    scheduleModuleBuildPoll();
}

/**
 * 并行读取模块自带 UI 清单并记录逐模块错误。
 *
 * 副作用：写入 state.moduleUi 与 state.moduleErrors。失败项会进入模块管理信息和通用
 * 降级视图，其他模块仍继续加载。
 */
async function loadModuleUis() {
    const results = await Promise.allSettled(state.modules.map(async (module) => {
        const payload = await api.getModuleUi(module.moduleId);
        return [module.moduleId, normalizeModuleUi(module.moduleId, payload)];
    }));
    const loaded = [];
    results.forEach((result, index) => {
        const moduleId = state.modules[index]?.moduleId;
        if (!moduleId) {
            return;
        }
        if (result.status === "fulfilled") {
            loaded.push({ moduleId, ui: result.value[1] });
        } else {
            loaded.push({ moduleId, error: errorMessage(result.reason) });
        }
    });
    const resolved = resolveModuleUiCatalog(state.modules, loaded);
    state.moduleUi = resolved.moduleUi;
    state.moduleErrors = resolved.moduleErrors;
}

function catalogIssues(payload) {
    if (!payload || typeof payload !== "object" || !Array.isArray(payload.issues)) {
        return [];
    }
    return payload.issues
        .filter((issue) => issue && typeof issue === "object")
        .map((issue) => ({ ...issue }));
}

/**
 * 重新读取业务与系统模块目录，并合并到当前偏好状态。
 *
 * 模块治理 Action 可能由 AI 直接执行，不经过模组设置自己的保存回调；因此任何读取
 * 管理快照或 AI 执行结束的路径都必须重新读取宿主的 enabled 事实，不能只复用
 * 启动时缓存的模块数组。
 *
 * @returns {Promise<object>} 规范化后的模块目录
 */
async function refreshModuleCatalogState() {
    const [businessPayload, systemPayload] = await Promise.all([
        api.getModules(),
        api.getSystemModules()
    ]);
    const catalogs = normalizeModuleCatalogs(businessPayload, systemPayload);
    state.moduleCatalogIssues = [
        ...catalogIssues(businessPayload),
        ...catalogIssues(systemPayload)
    ];
    applyCatalogState(catalogs, state.modulePreferences);
    return catalogs;
}

function issuesForModule(moduleId) {
    return state.moduleCatalogIssues.filter((issue) =>
        String(issue.id ?? issue.moduleId ?? "") === moduleId);
}

function moduleSettings(moduleId) {
    return { ...(state.modulePreferences.moduleSettings[moduleId] ?? {}) };
}

function managementModules() {
    return sortModules(
        state.modules,
        state.modulePreferences.managementOrder
    );
}

function managementModuleEntries() {
    return managementModules().map((module, managementOrder) => {
        const ui = state.moduleUi[module.moduleId] ?? null;
        const settings = moduleSettings(module.moduleId);
        const issues = [
            ...issuesForModule(module.moduleId),
            ...(state.moduleErrors[module.moduleId]
                ? [{
                    id: module.moduleId,
                    code: "MODULE_UI_LOAD_FAILED",
                    message: state.moduleErrors[module.moduleId]
                }]
                : [])
        ];
        const issue = issues.map((item) => item.message || item.code).filter(Boolean).join("；");
        return {
            moduleId: module.moduleId,
            title: module.title,
            description: module.description,
            order: module.order,
            managementOrder,
            enabled: module.enabled !== false,
            system: module.system ?? null,
            module: { ...module, ui },
            ui,
            settings,
            moduleSettings: settings,
            issue,
            issues
        };
    });
}

function systemAdminSnapshot(extra = {}) {
    return {
        modules: managementModuleEntries(),
        settingValues: { ...state.modulePreferences.moduleSettings },
        interfaceState: { ...state.moduleInterfaceState },
        issues: [
            ...state.moduleCatalogIssues,
            ...state.moduleRefreshIssues,
            ...moduleUiIssues()
        ],
        ...extra
    };
}

function moduleUiIssues() {
    return state.modules
        .filter(isBusinessModule)
        .filter((module) => state.moduleErrors[module.moduleId])
        .map((module) => ({
            id: module.moduleId,
            code: "MODULE_UI_LOAD_FAILED",
            message: state.moduleErrors[module.moduleId]
        }));
}

function businessModuleOrThrow(moduleId) {
    const id = String(moduleId ?? "").trim();
    const module = state.modules.find((item) =>
        isBusinessModule(item) && item.moduleId === id);
    if (!module) {
        throw new TypeError(`未找到业务模块：${id || "(empty)"}`);
    }
    return module;
}

function requirePlainObject(value, label) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new TypeError(`${label}必须是对象`);
    }
    return value;
}

function replaceModule(moduleId, patch) {
    state.modules = state.modules.map((module) =>
        module.moduleId === moduleId ? { ...module, ...patch } : module);
}

function applyCatalogState(catalogs, preferences) {
    state.modules = catalogs.modules;
    state.modulePreferences = reconcileModulePreferences(catalogs.modules, preferences);
}

function activeViewBecameUnavailable() {
    if (state.activeView === "overview" || state.activeView === "history") {
        return false;
    }
    const buildSource = buildViewSource(state.activeView);
    if (buildSource) {
        return !pendingBuildEntry(buildSource);
    }
    return !moduleForView(state.activeView);
}

function refreshVisibleFrameworkViews() {
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        void loadOverviewCounts();
        renderModuleCards();
    }
    if (document.querySelector("#settings-dialog")?.open) {
        renderSettings();
    }
}

function buildViewSource(view) {
    const text = String(view ?? "");
    return text.startsWith("build:") ? text.slice("build:".length) : "";
}

function buildViewId(sourceName) {
    return `build:${sourceName}`;
}

function pendingBuildEntry(sourceName) {
    return pendingModuleBuilds(state.moduleBuilds, state.modules)
        .find((entry) => entry.sourceName === sourceName) ?? null;
}

function refreshModuleBuildViews() {
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    const sourceName = buildViewSource(state.activeView);
    if (sourceName) {
        renderModuleBuildView(sourceName);
    }
    if (state.activeView === "overview" && document.querySelector("#module-build-strip")) {
        renderModuleBuildOverview();
    }
}

/**
 * 轮询后台构建快照，并在模块就绪后重新读取宿主目录。
 *
 * @returns {Promise<void>}
 */
async function refreshModuleBuilds() {
    if (moduleBuildReloadInFlight) {
        return;
    }
    moduleBuildReloadInFlight = true;
    try {
        const payload = await api.getModuleBuilds();
        state.moduleBuilds = normalizeModuleBuildSnapshot(payload);
        state.moduleBuildLoading = false;
        const readySources = pendingModuleBuilds(state.moduleBuilds, state.modules)
            .filter((entry) => entry.status === "READY")
            .map((entry) => entry.sourceName);
        if (readySources.length > 0) {
            const activeSource = buildViewSource(state.activeView);
            await reloadModuleCatalogAfterRefresh({});
            const loaded = state.modules.find((module) =>
                readySources.includes(module.moduleId));
            if (loaded && activeSource && readySources.includes(activeSource)) {
                await navigate(moduleNavigationItem(loaded).id);
                return;
            }
        }
        refreshModuleBuildViews();
    } catch (error) {
        state.moduleBuildLoading = false;
        toast(`模块构建状态刷新失败：${errorMessage(error)}`, { error: true });
    } finally {
        moduleBuildReloadInFlight = false;
    }
}

function scheduleModuleBuildPoll() {
    if (moduleBuildPollTimer != null) {
        window.clearTimeout(moduleBuildPollTimer);
        moduleBuildPollTimer = null;
    }
    const pending = pendingModuleBuilds(state.moduleBuilds, state.modules);
    const active = pending.some((entry) =>
        entry.status === "QUEUED" || entry.status === "BUILDING");
    if (state.moduleBuilds.mode !== "background" || (!active && !buildViewSource(state.activeView))) {
        return;
    }
    moduleBuildPollTimer = window.setTimeout(async () => {
        moduleBuildPollTimer = null;
        await refreshModuleBuilds();
        scheduleModuleBuildPoll();
    }, 1000);
}

async function openModuleBuild(sourceName) {
    const entry = pendingBuildEntry(sourceName);
    if (!entry) {
        toast("该模块已不在后台构建队列中，正在刷新目录。", { error: true });
        await refreshModuleBuilds();
        return;
    }
    try {
        const payload = await api.prioritizeModuleBuild(sourceName);
        state.moduleBuilds = normalizeModuleBuildSnapshot(payload);
        toast(`已将 ${entry.title} 移到优先构建位置。`);
    } catch (error) {
        toast(`优先构建请求失败：${errorMessage(error)}`, { error: true });
        return;
    }
    await navigate(buildViewId(sourceName));
    scheduleModuleBuildPoll();
}

function renderModuleBuildView(sourceName) {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    const entry = pendingBuildEntry(sourceName);
    if (!entry) {
        return;
    }
    const panel = element("section", "panel module-build-panel");
    panel.dataset.buildStatus = entry.status;
    const header = element("header", "panel__header");
    const copy = element("div");
    copy.append(
        element("p", "eyebrow", "MODULE BUILD"),
        element("h1", "", entry.title),
        element("p", "", entry.caption || "该模块正在由后台准备。")
    );
    header.append(copy, element(
        "span",
        `status-chip module-build-chip module-build-chip--${entry.status.toLowerCase()}`,
        moduleBuildStatusLabel(entry.status)
    ));
    const body = element("div", "module-build-panel__body");
    body.append(
        element(
            "p",
            "module-build-message",
            entry.message || "后台构建器将在当前模块完成后自动启用它。"
        ),
        element(
            "p",
            "muted",
            entry.status === "FAILED"
                ? "可点击“重试并优先构建”重新排队；失败模块不会提供任何动作或查询。"
                : "框架和其他已加载模块可继续使用；此页面不会伪造模块能力。"
        )
    );
    const actions = element("div", "module-build-actions");
    const retry = element(
        "button",
        "primary-button",
        entry.status === "FAILED" ? "重试并优先构建" : "优先构建此模块"
    );
    retry.type = "button";
    retry.addEventListener("click", () => void openModuleBuild(sourceName));
    const back = element("button", "text-button", "返回总览");
    back.type = "button";
    back.addEventListener("click", () => void navigate("overview"));
    actions.append(retry, back);
    body.append(actions);
    panel.append(header, body);
    main.replaceChildren(panel);
}

/**
 * AI 请求前等待全部业务模块完成构建。
 *
 * 用户明确取消等待时返回 false；存在失败模块或等待超时会抛出错误，调用方不会提交
 * 半套工具目录给模型。等待阶段不产生任何 Action 或执行记录。
 *
 * @returns {Promise<boolean>} 是否继续 AI 回合
 */
async function waitForModulesBeforeAiTurn() {
    const pending = pendingModuleBuilds(state.moduleBuilds, state.modules);
    if (pending.length === 0) {
        return true;
    }
    const waiting = pending.filter((entry) =>
        entry.status === "QUEUED" || entry.status === "BUILDING" || entry.status === "READY");
    const failed = pending.filter((entry) => entry.status === "FAILED");
    if (waiting.length === 0 && failed.length > 0) {
        throw new Error("仍有模块构建失败，请先在模块导航中重试或打开模块设置。");
    }
    if (!window.confirm(
        `还有 ${pending.length} 个模块尚未全部就绪。AI 将在模块构建完成后继续本次指令。是否等待？`
    )) {
        toast("已取消本次 AI 指令；模块不会因此停止构建。");
        return false;
    }

    toast("AI 已暂存本次指令，等待模块全部就绪后继续。");
    const deadline = Date.now() + (10 * 60 * 1000);
    while (Date.now() < deadline) {
        const current = pendingModuleBuilds(state.moduleBuilds, state.modules);
        if (current.length === 0) {
            return true;
        }
        const currentFailed = current.filter((entry) => entry.status === "FAILED");
        if (currentFailed.length > 0
            && current.every((entry) => entry.status === "FAILED")) {
            throw new Error(
                `模块构建失败：${currentFailed.map((entry) => entry.title).join("、")}`
            );
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
        await refreshModuleBuilds();
    }
    throw new Error("等待模块构建完成超时，请稍后重试。");
}

/**
 * 在 AI 动作完成后刷新受影响数据，不绑定具体业务模块。
 *
 * @param {object | null} execution 执行记录
 */
async function refreshAfterAiExecution(execution) {
    const changedModules = executionCompletedModules(execution);
    if (changedModules.length === 0) {
        return;
    }
    try {
        await refreshModuleCatalogState();
        refreshVisibleFrameworkViews();
        if (activeViewBecameUnavailable()) {
            await navigate("overview");
            return;
        }
    } catch {
        // 目录刷新是执行完成后的辅助同步；失败时仍尝试重载当前受影响模块。
    }
    const activeModule = moduleForView(state.activeView);
    if (activeModule && changedModules.includes(activeModule.moduleId)) {
        await navigate(state.activeView);
    }
}

/**
 * 宿主刷新模块目录后的前端重建流程。
 *
 * 操作顺序刻意保持为“读取新目录 -> 合并偏好 -> 重载 UI -> 保存被纠正的顺序 ->
 * 重新渲染”。若当前视图随模块停用而失效，则回到总览；刷新失败保留现有可用视图。
 */
async function reloadModuleCatalogAfterRefresh(refreshPayload) {
    const [moduleResult, systemModuleResult, preferenceResult, buildResult] = await Promise.allSettled([
        api.getModules(),
        api.getSystemModules(),
        api.getModulePreferences(),
        api.getModuleBuilds()
    ]);
    if (moduleResult.status === "rejected" || systemModuleResult.status === "rejected") {
        const failure = moduleResult.status === "rejected"
            ? moduleResult.reason
            : systemModuleResult.reason;
        throw new Error(`模块目录重新读取失败：${errorMessage(failure)}`);
    }
    const catalogs = normalizeModuleCatalogs(moduleResult.value, systemModuleResult.value);
    state.moduleCatalogIssues = [
        ...catalogIssues(moduleResult.value),
        ...catalogIssues(systemModuleResult.value)
    ];
    state.moduleRefreshIssues = Array.isArray(refreshPayload?.issues)
        ? refreshPayload.issues.filter((issue) => issue && typeof issue === "object")
        : [];
    const fetchedPreferences = preferenceResult.status === "fulfilled"
        ? normalizeModulePreferences(preferenceResult.value)
        : normalizeModulePreferences({});
    const preservedPreferences = {
        sidebarOrder: state.modulePreferences.sidebarOrder.length
            ? state.modulePreferences.sidebarOrder
            : fetchedPreferences.sidebarOrder,
        managementOrder: state.modulePreferences.managementOrder.length
            ? state.modulePreferences.managementOrder
            : fetchedPreferences.managementOrder,
        moduleSettings: preferenceResult.status === "fulfilled"
            ? fetchedPreferences.moduleSettings
            : state.modulePreferences.moduleSettings
    };
    applyCatalogState(catalogs, preservedPreferences);
    if (buildResult.status === "fulfilled") {
        state.moduleBuilds = normalizeModuleBuildSnapshot(buildResult.value);
        state.moduleBuildLoading = false;
    }
    await loadModuleUis();
    state.moduleAssetVersion = String(Date.now());

    const persistIssues = [];
    if (preferenceResult.status === "rejected") {
        persistIssues.push({
            code: "MODULE_PREFERENCES_LOAD_FAILED",
            message: errorMessage(preferenceResult.reason)
        });
    }
    const orderJobs = [];
    if (!sameStringArray(
        state.modulePreferences.sidebarOrder,
        fetchedPreferences.sidebarOrder
    )) {
        orderJobs.push(api.saveSidebarModuleOrder(state.modulePreferences.sidebarOrder));
    }
    if (!sameStringArray(
        state.modulePreferences.managementOrder,
        fetchedPreferences.managementOrder
    )) {
        orderJobs.push(api.saveManagementModuleOrder(state.modulePreferences.managementOrder));
    }
    const orderResults = await Promise.allSettled(orderJobs);
    orderResults.forEach((result) => {
        if (result.status === "rejected") {
            persistIssues.push({
                code: "MODULE_ORDER_SAVE_FAILED",
                message: errorMessage(result.reason)
            });
        }
    });

    if (activeViewBecameUnavailable()) {
        await navigate("overview");
    } else {
        refreshVisibleFrameworkViews();
    }
    scheduleModuleBuildPoll();
    const source = refreshPayload && typeof refreshPayload === "object"
        ? refreshPayload
        : {};
    const issues = [
        ...state.moduleCatalogIssues,
        ...state.moduleRefreshIssues,
        ...moduleUiIssues(),
        ...persistIssues
    ];
    return {
        ...systemAdminSnapshot(),
        added: Array.isArray(source.added) ? source.added : [],
        issues,
        revision: source.revision ?? null,
        needsBuild: issues.some((issue) => String(issue?.code ?? "").includes("NOT_BUILT"))
    };
}

function sameStringArray(left, right) {
    return left.length === right.length && left.every((value, index) => value === right[index]);
}

function createSystemAdminApi() {
    return Object.freeze({
        async getState() {
            await Promise.all([
                refreshModuleCatalogState(),
                systemAdminApi.getInterfaceState()
            ]);
            return systemAdminSnapshot();
        },

        async getInterfaceState() {
            const normalized = normalizeModuleInterfaceState(
                await api.getModuleInterfaceState()
            );
            state.moduleInterfaceState = normalized;
            return normalized;
        },

        listManagementModules() {
            return managementModuleEntries();
        },

        listModules() {
            return managementModuleEntries();
        },

        async setModuleEnabled(moduleId, enabled) {
            const module = businessModuleOrThrow(moduleId);
            const payload = await api.setModuleEnabled(module.moduleId, enabled === true);
            const applied = payload && typeof payload === "object"
                && typeof payload.enabled === "boolean"
                ? payload.enabled
                : enabled === true;
            replaceModule(module.moduleId, { enabled: applied });
            if (activeViewBecameUnavailable()) {
                await navigate("overview");
            } else {
                refreshVisibleFrameworkViews();
            }
            return {
                ...systemAdminSnapshot(),
                moduleId: module.moduleId,
                enabled: applied
            };
        },

        refreshModules() {
            return api.refreshSystemModules()
                .then((payload) => reloadModuleCatalogAfterRefresh(payload));
        },

        openModuleFolder() {
            return api.openModuleFolder();
        },

        async saveManagementOrder(moduleIds) {
            const ids = Array.isArray(moduleIds) ? moduleIds : [];
            const next = reconcileModulePreferences(state.modules, {
                ...state.modulePreferences,
                managementOrder: ids
            });
            await api.saveManagementModuleOrder(next.managementOrder);
            state.modulePreferences = {
                ...state.modulePreferences,
                managementOrder: next.managementOrder
            };
            return systemAdminSnapshot();
        },

        async saveModuleSettings(moduleId, values) {
            const module = businessModuleOrThrow(moduleId);
            const nextValues = requirePlainObject(values, "模块设置值");
            const payload = await api.saveModuleSettings(module.moduleId, nextValues);
            const saved = payload && typeof payload === "object" && !Array.isArray(payload)
                ? { ...payload }
                : { ...nextValues };
            state.modulePreferences = {
                ...state.modulePreferences,
                moduleSettings: {
                    ...state.modulePreferences.moduleSettings,
                    [module.moduleId]: saved
                }
            };
            return { ...saved };
        },

        async resetModuleSettings(moduleId) {
            const module = businessModuleOrThrow(moduleId);
            const payload = await api.resetModuleSettings(module.moduleId);
            const reset = payload && typeof payload === "object" && !Array.isArray(payload)
                ? { ...payload }
                : {};
            state.modulePreferences = {
                ...state.modulePreferences,
                moduleSettings: {
                    ...state.modulePreferences.moduleSettings,
                    [module.moduleId]: reset
                }
            };
            return { ...reset };
        },

        async setInterfaceAiCallable(moduleId, kind, interfaceId, aiCallable) {
            const module = businessModuleOrThrow(moduleId);
            const normalizedKind = kind === "query" ? "query" : "action";
            state.moduleInterfaceState = normalizeModuleInterfaceState(
                await api.setInterfaceAiCallable(
                    module.moduleId,
                    normalizedKind,
                    interfaceId,
                    aiCallable === true
                )
            );
            return systemAdminSnapshot({
                moduleId: module.moduleId,
                kind: normalizedKind,
                interfaceId,
                aiCallable: aiCallable === true
            });
        },

        getManagementOrder() {
            return [...state.modulePreferences.managementOrder];
        }
    });
}

/**
 * 绑定框架级按钮、快捷键和指针入口。
 *
 * 副作用：向 window/document/sidebar 注册监听器。只在当前页面生命周期内有效，
 * pagehide 会终止侧栏拖动和事件流，避免异步回调在页面离开后继续更新 DOM。
 */
function bindGlobalControls() {
    document.querySelector("#open-settings")?.addEventListener("click", openSettings);
    document.querySelector("#exit-orderly")?.addEventListener(
        "click",
        () => void requestHostShutdown()
    );
    syncExitTrigger();
    document.querySelector("#mobile-menu")?.addEventListener("click", openMobileNav);
    document.querySelector("#mobile-scrim")?.addEventListener("click", () => closeMobileNav());

    const search = document.querySelector("#nav-search");
    search?.addEventListener("pointerdown", () => expandDesktopSidebarImmediately());
    search?.addEventListener("focus", () => expandDesktopSidebarImmediately());
    search?.addEventListener("click", () => expandDesktopSidebarImmediately());
    search?.addEventListener("input", () => {
        renderNavigation(search.value);
        if (search.value.trim()) {
            expandDesktopSidebarImmediately();
        } else {
            reconcileDesktopSidebar();
        }
    });
    search?.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            search.value = "";
            renderNavigation("");
            search.blur();
            reconcileDesktopSidebar();
        }
    });

    document.addEventListener("keydown", (event) => {
        const tagName = document.activeElement?.tagName;
        const isTyping = ["INPUT", "TEXTAREA", "SELECT"].includes(tagName);
        if (event.key === "/" && !isTyping) {
            event.preventDefault();
            focusDesktopSidebarSearch();
        }
    });
    document.addEventListener("keydown", (event) => {
        const settingsDialog = document.querySelector("#settings-dialog");
        const sidebar = document.querySelector("#sidebar");
        if (event.key !== "Escape" || settingsDialog?.open) {
            return;
        }
        if (!window.matchMedia(MOBILE_NAV_QUERY).matches
            || !sidebar?.classList.contains("is-open")) {
            return;
        }
        event.preventDefault();
        closeMobileNav();
    }, { capture: true });

    bindDesktopSidebarControls();
    bindSidebarViewport();
    document.querySelector(".sidebar-scroll")?.addEventListener(
        "scroll",
        queueSidebarCountRender,
        { passive: true }
    );

    document.addEventListener("pointermove", moveNavigationDrag, { passive: false });
    document.addEventListener("pointerup", finishNavigationDrag);
    document.addEventListener("pointercancel", cancelNavigationDrag);
    bindHistoryViewRail();

    window.addEventListener("pagehide", () => {
        cancelNavigationDrag();
        eventStream?.close();
    }, { once: true });
}

/**
 * 绑定历史记录视图切换栏。
 */
function bindHistoryViewRail() {
    document.querySelectorAll("[data-history-view]").forEach((button) => {
        button.addEventListener("click", () => {
            const view = String(button.dataset.historyView ?? "");
            if (!HISTORY_VIEWS.has(view) || state.historyView === view) {
                return;
            }
            state.historyView = view;
            syncHistoryViewRail();
            if (state.activeView === "history") {
                renderHistory();
                document.querySelector("#main-content")?.focus({ preventScroll: true });
            }
        });
    });
    syncHistoryViewRail();
}

/**
 * 同步历史记录视图切换栏的可见性和选中态。
 */
function syncHistoryViewRail() {
    const rail = document.querySelector("#history-view-rail");
    if (!rail) {
        return;
    }
    rail.hidden = state.activeView !== "history";
    rail.querySelectorAll("[data-history-view]").forEach((button) => {
        button.setAttribute(
            "aria-pressed",
            String(button.dataset.historyView === state.historyView)
        );
    });
}

/**
 * 绑定桌面侧栏的悬停、焦点与指针精度变化。
 *
 * 侧栏展开不是独立页面状态：指针离开、焦点离开、搜索有值、正在排序或设置弹窗打开
 * 都会阻止收起。所有延迟统一走 desktopSidebar.timer，确保只保留一个待执行状态。
 */
function bindDesktopSidebarControls() {
    const sidebar = document.querySelector("#sidebar");
    if (!sidebar) {
        return;
    }

    sidebar.addEventListener("pointerenter", () => {
        if (!desktopSidebar.active
            || !window.matchMedia(SIDEBAR_POINTER_QUERY).matches) {
            return;
        }
        desktopSidebar.pointerInside = true;
        scheduleDesktopSidebarExpansion();
    });
    sidebar.addEventListener("pointerleave", () => {
        if (!desktopSidebar.active) {
            return;
        }
        desktopSidebar.pointerInside = false;
        reconcileDesktopSidebar();
    });
    sidebar.addEventListener("focusin", () => {
        if (desktopSidebar.active) {
            expandDesktopSidebarImmediately();
        }
    });
    sidebar.addEventListener("focusout", () => {
        window.queueMicrotask(reconcileDesktopSidebar);
    });

    const pointerViewport = window.matchMedia(SIDEBAR_POINTER_QUERY);
    pointerViewport.addEventListener("change", () => {
        desktopSidebar.pointerInside = false;
        reconcileDesktopSidebar();
    });
}

/**
 * 根据当前视口在桌面图标态和移动抽屉态之间重置侧栏。
 */
function bindSidebarViewport() {
    const mobileViewport = window.matchMedia(MOBILE_NAV_QUERY);
    mobileViewport.addEventListener("change", (event) => {
        const sidebar = document.querySelector("#sidebar");
        if (event.matches) {
            clearSidebarCountLayer();
            deactivateDesktopSidebar();
            if (sidebar?.contains(document.activeElement)) {
                document.querySelector("#mobile-menu")?.focus({ preventScroll: true });
            }
            syncMobileSidebarAvailability();
            return;
        }
        closeMobileNav({ restoreFocus: false });
        activateDesktopSidebar();
    });
    window.addEventListener("resize", queueSidebarCountRender, { passive: true });

    if (mobileViewport.matches) {
        clearSidebarCountLayer();
        deactivateDesktopSidebar();
        syncMobileSidebarAvailability();
    } else {
        activateDesktopSidebar();
    }
}

function activateDesktopSidebar() {
    const sidebar = document.querySelector("#sidebar");
    if (!sidebar || !window.matchMedia(DESKTOP_SIDEBAR_QUERY).matches) {
        return;
    }
    desktopSidebar.active = true;
    desktopSidebar.pointerInside = false;
    setDesktopSidebarExpansion(false);
    sidebar.inert = false;
    sidebar.removeAttribute("aria-hidden");
    queueSidebarCountRender();
}

// 切换到移动抽屉时必须同时清掉桌面侧栏的延迟、动画帧、transitionend 回调和计数层，
// 否则窄屏会出现旧定时器继续写桌面几何状态的问题。
function deactivateDesktopSidebar() {
    clearDesktopSidebarTimer();
    clearSidebarAttention();
    cancelSidebarCountTracking();
    cancelSidebarCountRefresh();
    clearSidebarCountLayer();
    desktopSidebar.active = false;
    desktopSidebar.pointerInside = false;
    desktopSidebar.expanded = false;
    const sidebar = document.querySelector("#sidebar");
    sidebar?.classList.remove("is-expanded");
    if (sidebar && !sidebar.classList.contains("is-open")) {
        sidebar.dataset.motionState = "collapsed";
        sidebar.dataset.motionPhase = "active";
    }
}

// 展开与收起共用同一个状态入口；动效关闭时立即完成计数重绘，有动效时由帧循环和
// transitionend 双保险收尾，避免只依赖主题是否声明 transition 而导致状态悬挂。
function setDesktopSidebarExpansion(expanded) {
    const sidebar = document.querySelector("#sidebar");
    if (!sidebar || !desktopSidebar.active) {
        return;
    }
    const wasExpanded = desktopSidebar.expanded;
    const changed = wasExpanded !== expanded;
    const duration = sidebarTransitionDurationMs();
    desktopSidebar.expanded = expanded;
    sidebar.classList.toggle("is-expanded", expanded);
    if (changed) {
        if (duration <= 0) {
            cancelSidebarCountTracking();
            cancelSidebarCountRefresh();
            renderSidebarCountLayer();
        } else if (expanded) {
            beginSidebarCountTracking();
        } else {
            scheduleSidebarCountRefreshAfterCollapse();
        }
    } else if (expanded) {
        queueSidebarCountRender();
    }
    if (desktopSidebar.attentionTimer == null) {
        sidebar.dataset.motionState = expanded ? "expanded" : "collapsed";
    }
    sidebar.dataset.motionPhase = "active";
}

function expandDesktopSidebarImmediately() {
    if (!desktopSidebar.active) {
        return;
    }
    clearDesktopSidebarTimer();
    setDesktopSidebarExpansion(true);
}

function focusDesktopSidebarSearch() {
    const search = document.querySelector("#nav-search");
    if (!desktopSidebar.active) {
        return;
    }
    expandDesktopSidebarImmediately();
    search?.focus({ preventScroll: true });
}

function scheduleDesktopSidebarExpansion() {
    if (!desktopSidebar.active || desktopSidebar.expanded) {
        clearDesktopSidebarTimer();
        return;
    }
    scheduleDesktopSidebarTransition(
        () => setDesktopSidebarExpansion(true),
        SIDEBAR_EXPAND_DELAY_MS
    );
}

function desktopSidebarCollapseBlocked() {
    const sidebar = document.querySelector("#sidebar");
    const search = document.querySelector("#nav-search");
    const dialog = document.querySelector("#settings-dialog");
    return desktopSidebar.pointerInside
        || Boolean(sidebar?.contains(document.activeElement))
        || Boolean(search?.value.trim())
        || navigationDrag?.active === true
        || document.body.classList.contains("is-navigation-dragging")
        || dialog?.open === true;
}

function reconcileDesktopSidebar() {
    if (!desktopSidebar.active) {
        return;
    }
    if (desktopSidebarCollapseBlocked()) {
        clearDesktopSidebarTimer();
        return;
    }
    if (!desktopSidebar.expanded) {
        clearDesktopSidebarTimer();
        return;
    }
    scheduleDesktopSidebarTransition(
        () => setDesktopSidebarExpansion(false),
        SIDEBAR_COLLAPSE_DELAY_MS
    );
}

function scheduleDesktopSidebarTransition(callback, delay) {
    clearDesktopSidebarTimer();
    desktopSidebar.timer = window.setTimeout(() => {
        desktopSidebar.timer = null;
        callback();
    }, delay);
}

function clearDesktopSidebarTimer() {
    if (desktopSidebar.timer == null) {
        return;
    }
    window.clearTimeout(desktopSidebar.timer);
    desktopSidebar.timer = null;
}

function clearSidebarAttention() {
    if (desktopSidebar.attentionTimer == null) {
        return;
    }
    window.clearTimeout(desktopSidebar.attentionTimer);
    desktopSidebar.attentionTimer = null;
}

function queueSidebarCountRender() {
    if (sidebarCountFrame != null) {
        return;
    }
    sidebarCountFrame = requestSidebarFrame(() => {
        sidebarCountFrame = null;
        renderSidebarCountLayer();
    });
}

function requestSidebarFrame(callback) {
    return window.requestAnimationFrame
        ? window.requestAnimationFrame(callback)
        : window.setTimeout(callback, 0);
}

function cancelSidebarFrame(frame) {
    if (window.requestAnimationFrame) {
        window.cancelAnimationFrame(frame);
    } else {
        window.clearTimeout(frame);
    }
}

function navigationTextRect(button) {
    const text = button.querySelector(".nav-copy strong");
    if (!text) {
        return null;
    }
    const range = document.createRange();
    range.selectNodeContents(text);
    const rect = range.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 ? rect : null;
}

function clipInsetValues(clipPath) {
    const match = /^inset\((.*)\)$/.exec(String(clipPath ?? "").trim());
    if (!match) {
        return [];
    }
    const values = [];
    let current = "";
    let depth = 0;
    for (const character of match[1]) {
        if (character === "(") {
            depth += 1;
        } else if (character === ")") {
            depth = Math.max(0, depth - 1);
        }
        if (/\s/.test(character) && depth === 0) {
            if (current) {
                values.push(current);
                current = "";
            }
        } else {
            current += character;
        }
    }
    if (current) {
        values.push(current);
    }
    const roundIndex = values.indexOf("round");
    return roundIndex === -1 ? values : values.slice(0, roundIndex);
}

function clipInsetLength(value, basis) {
    if (value === "0") {
        return 0;
    }
    const simple = /^(-?\d+(?:\.\d+)?)(px|%)$/.exec(value ?? "");
    if (simple) {
        const amount = Number(simple[1]);
        return simple[2] === "%" ? basis * amount / 100 : amount;
    }
    const calc = /^calc\(\s*(\d+(?:\.\d+)?)%\s*-\s*(\d+(?:\.\d+)?)px\s*\)$/
        .exec(value ?? "");
    if (calc) {
        return basis * Number(calc[1]) / 100 - Number(calc[2]);
    }
    return null;
}

function sidebarVisibleWidth(sidebarWidth, clipPath) {
    const values = clipInsetValues(clipPath);
    if (!values.length) {
        return sidebarWidth;
    }
    const rightValue = values.length >= 2 ? values[1] : values[0];
    const leftValue = values.length >= 4
        ? values[3]
        : values.length >= 2 ? values[1] : values[0];
    const left = clipInsetLength(leftValue, sidebarWidth) ?? 0;
    const right = clipInsetLength(rightValue, sidebarWidth) ?? 0;
    return Math.max(0, Math.min(sidebarWidth, sidebarWidth - left - right));
}

function isSidebarClipTransition(event, sidebar) {
    return event.target === sidebar && event.propertyName === "clip-path";
}

function renderSidebarCountLayer() {
    const layer = document.querySelector("#sidebar-count-layer");
    if (!layer) {
        return;
    }
    if (!desktopSidebar.active
        || window.matchMedia(MOBILE_NAV_QUERY).matches) {
        layer.replaceChildren();
        return;
    }

    const sidebar = document.querySelector("#sidebar");
    if (!sidebar) {
        layer.replaceChildren();
        return;
    }
    const existing = new Map(
        [...layer.querySelectorAll(".sidebar-count-bubble[data-module-id]")]
            .map((bubble) => [bubble.dataset.moduleId, bubble])
    );
    const entries = [];
    document.querySelectorAll(".nav-item--module[data-module-id]").forEach((button) => {
        const moduleId = button.dataset.moduleId;
        const count = navigationCount(moduleId);
        if (count == null) {
            return;
        }
        const bubble = existing.get(moduleId)
            ?? element("span", "sidebar-count-bubble", String(count));
        if (!existing.delete(moduleId)) {
            layer.append(bubble);
        }
        if (bubble.textContent !== String(count)) {
            bubble.textContent = String(count);
        }
        bubble.dataset.moduleId = moduleId;
        entries.push({ bubble, button });
    });
    existing.forEach((bubble) => bubble.remove());

    const sidebarRect = sidebar.getBoundingClientRect();
    const clipPath = window.getComputedStyle(sidebar).clipPath;
    const visibleWidth = sidebarVisibleWidth(sidebarRect.width, clipPath);
    const railWidth = sidebarRailWidth();
    const railRight = sidebarRect.left + railWidth;
    const expansionProgress = Math.max(
        0,
        Math.min(
            1,
            (visibleWidth - railWidth) / Math.max(1, sidebarRect.width - railWidth)
        )
    );
    const measurements = entries.map(({ bubble, button }) => {
        const rect = button.getBoundingClientRect();
        const textRect = navigationTextRect(button);
        const handleRect = button.querySelector(".nav-drag-handle")
            ?.getBoundingClientRect();
        return {
            bubble,
            bubbleWidth: bubble.getBoundingClientRect().width,
            handleRect,
            rect,
            textRect
        };
    });

    measurements.forEach(({ bubble, bubbleWidth, handleRect, rect, textRect }) => {
        if (rect.width <= 0 || rect.height <= 0) {
            bubble.remove();
            return;
        }
        const collapsedLeft = railRight + 4;
        const expandedLeft = textRect ? textRect.right + 7 : collapsedLeft;
        let left = collapsedLeft
            + (expandedLeft - collapsedLeft) * expansionProgress;
        if (handleRect?.width > 0
            && left - 6 < handleRect.right
            && left + bubbleWidth > handleRect.left) {
            left = handleRect.right + 7;
        }
        left = Math.max(railRight + 4, left);
        const currentVisibleRight = sidebarRect.left + visibleWidth;
        if (sidebarCountTrackingFrame != null
            && visibleWidth > railWidth
            && left + bubbleWidth > currentVisibleRight) {
            left = currentVisibleRight - bubbleWidth;
        }
        bubble.style.left = `${Math.round(left)}px`;
        bubble.style.top = `${Math.round(rect.top + (rect.height / 2))}px`;
    });
}

function clearSidebarCountLayer() {
    document.querySelector("#sidebar-count-layer")?.replaceChildren();
}

function sidebarRailWidth() {
    const raw = window.getComputedStyle(document.documentElement)
        .getPropertyValue("--sidebar-width")
        .trim();
    const width = Number.parseFloat(raw);
    return Number.isFinite(width) && width > 0 ? width : 72;
}

function sidebarTransitionDurationMs() {
    const raw = window.getComputedStyle(document.documentElement)
        .getPropertyValue("--duration-sidebar")
        .trim();
    const value = Number.parseFloat(raw);
    if (!Number.isFinite(value)) {
        return SIDEBAR_COLLAPSE_DELAY_MS;
    }
    return raw.endsWith("ms") ? value : value * 1000;
}

// 收起动画期间数字气泡必须跟随裁切边界移动；帧循环负责视觉平滑，transitionend
// 与超时负责终止，防止主题未触发过渡事件或页面后台丢帧后留下永久循环。
function scheduleSidebarCountRefreshAfterCollapse() {
    cancelSidebarCountTracking();
    cancelSidebarCountRefresh();
    const sidebar = document.querySelector("#sidebar");
    const handler = (event) => {
        if (!isSidebarClipTransition(event, sidebar)) {
            return;
        }
        finishSidebarCountTracking();
    };
    sidebarCountTransitionHandler = handler;
    sidebar?.addEventListener("transitionend", handler);
    startSidebarCountFrameLoop();
    sidebarCountRefreshTimer = window.setTimeout(
        finishSidebarCountTracking,
        sidebarTransitionDurationMs() + 32
    );
    renderSidebarCountLayer();
}

// 展开动画从第一帧开始跟踪真实 clip-path 宽度，而不是预先假设动画时长。
function beginSidebarCountTracking() {
    cancelSidebarCountTracking();
    cancelSidebarCountRefresh();
    const sidebar = document.querySelector("#sidebar");
    if (!desktopSidebar.active || !sidebar) {
        return;
    }

    const finish = (event) => {
        if (event && !isSidebarClipTransition(event, sidebar)) {
            return;
        }
        finishSidebarCountTracking();
    };
    sidebarCountTrackingHandler = finish;
    sidebar.addEventListener("transitionend", finish);
    startSidebarCountFrameLoop();
    sidebarCountRefreshTimer = window.setTimeout(
        finishSidebarCountTracking,
        sidebarTransitionDurationMs() + 32
    );
    renderSidebarCountLayer();
}

function startSidebarCountFrameLoop() {
    const track = () => {
        if (!desktopSidebar.active
            || window.matchMedia(MOBILE_NAV_QUERY).matches) {
            sidebarCountTrackingFrame = null;
            return;
        }
        renderSidebarCountLayer();
        sidebarCountTrackingFrame = requestSidebarFrame(track);
    };
    sidebarCountTrackingFrame = requestSidebarFrame(track);
}

function finishSidebarCountTracking() {
    cancelSidebarCountTracking();
    cancelSidebarCountRefresh();
    renderSidebarCountLayer();
}

function cancelSidebarCountTracking() {
    if (sidebarCountTrackingFrame != null) {
        cancelSidebarFrame(sidebarCountTrackingFrame);
        sidebarCountTrackingFrame = null;
    }
    if (sidebarCountTrackingHandler) {
        document.querySelector("#sidebar")?.removeEventListener(
            "transitionend",
            sidebarCountTrackingHandler
        );
        sidebarCountTrackingHandler = null;
    }
}

function cancelSidebarCountRefresh() {
    if (sidebarCountRefreshTimer != null) {
        window.clearTimeout(sidebarCountRefreshTimer);
        sidebarCountRefreshTimer = null;
    }
    if (sidebarCountTransitionHandler) {
        document.querySelector("#sidebar")?.removeEventListener(
            "transitionend",
            sidebarCountTransitionHandler
        );
        sidebarCountTransitionHandler = null;
    }
}

/**
 * 应用设置，但不重建设置面板或改变当前焦点。
 *
 * @param {object} settings 设置快照
 */
function applySettings(settings) {
    document.documentElement.dataset.motion = settings.motion;
    aiDialog?.setIcon(settings.aiIcon);
    syncSettingControls(settings);
    void applyThemeSelection(settings.theme);
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        renderModuleCards();
    }
}

/**
 * 应用目录主题，并把实际生效主题回写到本地设置。
 *
 * ThemeManager 用请求代次阻止慢响应覆盖新选择；这里再次比对 settingsStore 的当前值，
 * 防止用户在一次异步主题切换期间又选择了其他主题。副作用：插入/移除主题样式和元信息，
 * 更新设置状态文本，必要时生成局部切换告警。
 *
 * @param {string} themeId 目标主题
 */
async function applyThemeSelection(themeId) {
    const requested = String(themeId ?? "");
    updateSettingsStatus(requested ? "正在应用主题样式…" : "当前使用内置安全外观。");
    const theme = await themeManager.apply(requested);
    if (settingsStore.getSnapshot().theme !== requested) {
        return;
    }
    const patch = reconcileAppliedTheme(requested, theme);
    if (patch) {
        const actualId = patch.theme;
        const actualLabel = state.themes.find((item) => item.id === actualId)?.label || actualId;
        const message = actualId
            ? `请求的主题不可用，已应用“${actualLabel}”。`
            : "请求的主题不可用，已回退内置安全外观。";
        updateSettingsStatus(message);
        if (requested) {
            showThemeSwitchAlert(message);
        }
        settingsStore.update(patch);
        return;
    }
    if (theme && typeof theme.id === "string") {
        const label = state.themes.find((item) => item.id === theme.id)?.label || theme.id;
        updateSettingsStatus(`主题“${label}”已应用；目录变更需重启宿主后生效。`);
    } else if (!requested) {
        updateSettingsStatus("没有可用目录主题，当前使用内置安全外观。");
    } else if (state.themes.length > 0) {
        updateSettingsStatus("主题样式加载失败，已保留当前有效外观。");
    } else {
        updateSettingsStatus("没有可用目录主题，当前使用内置安全外观。");
    }
}

/**
 * 更新设置弹窗专用状态文本。
 *
 * @param {string} message 消息
 */
function updateSettingsStatus(message) {
    const status = document.querySelector("#settings-status");
    if (status) {
        status.textContent = message;
    }
}

/**
 * 仅在实际主题切换失败时创建局部告警。
 *
 * @param {string} message 消息
 */
function showThemeSwitchAlert(message) {
    const panel = document.querySelector("#settings-panel");
    if (!panel || state.settingsSection !== "appearance") {
        return;
    }
    let alert = document.querySelector("#theme-switch-alert");
    if (!alert || alert.parentElement !== panel) {
        alert = element("div", "theme-switch-alert");
        alert.id = "theme-switch-alert";
        alert.setAttribute("role", "alert");
        const header = panel.querySelector(".settings-panel__header");
        if (header) {
            header.after(alert);
        } else {
            panel.prepend(alert);
        }
    }
    alert.replaceChildren(
        element("strong", "", "主题切换未完成"),
        element("p", "", message)
    );
}

function clearThemeSwitchAlert() {
    document.querySelector("#theme-switch-alert")?.remove();
}

/**
 * 原地同步设置控件，避免表单重建导致焦点丢失。
 *
 * @param {object} settings 设置快照
 */
function syncSettingControls(settings) {
    document.querySelectorAll('#settings-panel input[name="theme"]').forEach((input) => {
        input.checked = input.value === settings.theme;
    });
    document.querySelectorAll('#settings-panel input[name="motion"]').forEach((input) => {
        input.checked = input.value === settings.motion;
    });
    document.querySelectorAll('#settings-panel input[name="aiIcon"]').forEach((input) => {
        input.checked = input.value === settings.aiIcon;
    });
    document.querySelectorAll("#settings-panel input[data-setting-key]").forEach((input) => {
        const key = input.dataset.settingKey;
        if (key === "notifications") {
            input.checked = settings.notifications;
        }
    });
}

/**
 * 更新本地服务和计划器状态。
 *
 * @param {unknown} error 连接错误
 */
function safeWorkspacePath() {
    return ".orderly-data/workspace";
}

function updateHealth(error = null) {
    const sandboxStatus = document.querySelector(".sandbox-status");
    const workspacePathNode = document.querySelector("#workspace-path");
    const planner = document.querySelector("#planner-badge");
    const online = !error && String(state.health?.status ?? "").toUpperCase() === "UP";
    const workspacePath = safeWorkspacePath();
    const statusText = online
        ? `工作区已就绪；路径 ${workspacePath}`
        : "工作区不可用；请启动本地 Java 宿主";

    sandboxStatus?.setAttribute("aria-label", statusText);
    if (workspacePathNode) {
        workspacePathNode.textContent = workspacePath;
    }
    if (planner) {
        const plannerStatus = String(state.health?.planner ?? "LOCAL_RULES").toUpperCase();
        planner.textContent = plannerStatus === "LOCAL_RULES"
            ? "本地规则计划器 · 未连接外部模型"
            : `${String(state.health?.planner ?? "计划器状态未知")} · 外部模型连接状态未声明`;
    }
}

function syncExitTrigger() {
    const button = document.querySelector("#exit-orderly");
    if (!button) {
        return;
    }
    button.disabled = state.executing || state.shutdownRequested;
    button.setAttribute("aria-busy", state.shutdownRequested ? "true" : "false");
    button.title = state.shutdownRequested
        ? "归序正在退出"
        : state.executing
            ? "执行中的任务完成后再退出"
            : "退出归序并停止本地服务";
}

/**
 * 请求宿主安全退出。
 *
 * 前端只负责确认和状态提示；执行互锁、审计和进程退出由宿主保证。宿主接受请求后，
 * start.ps1 会检测 Java 退出并清理 AI 服务与后台模块构建器。
 *
 * @returns {Promise<void>}
 */
async function requestHostShutdown() {
    if (state.shutdownRequested) {
        return;
    }
    if (state.executing) {
        toast("当前仍有 Action 或计划正在执行，完成后再退出。", { error: true });
        return;
    }
    const pendingBuilds = pendingModuleBuilds(state.moduleBuilds, state.modules)
        .filter((entry) => ["QUEUED", "BUILDING", "READY"].includes(entry.status));
    const buildNotice = pendingBuilds.length > 0
        ? `后台仍有 ${pendingBuilds.length} 个模块未完成构建，退出后将在下次启动时继续。\n`
        : "";
    if (!window.confirm(
        `退出归序将停止 Java 宿主、AI 服务和后台模块构建。\n${buildNotice}确认退出？`
    )) {
        return;
    }

    state.shutdownRequested = true;
    syncExitTrigger();
    document.querySelector(".app-shell")?.setAttribute("data-host-state", "stopping");
    setSettingsStatus("正在停止本地宿主与相关进程…");
    toast("正在退出归序，本地服务停止后此页面将无法继续操作。");
    try {
        const response = await api.shutdownHost();
        const message = response?.message || "正在停止本地宿主与相关进程";
        setSettingsStatus(message);
        toast(message);
    } catch (error) {
        state.shutdownRequested = false;
        document.querySelector(".app-shell")?.removeAttribute("data-host-state");
        syncExitTrigger();
        const message = `退出失败：${errorMessage(error)}`;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    }
}

/**
 * 渲染侧边栏导航，并应用搜索和模块显示偏好。
 *
 * @param {string} query 搜索词
 */
function renderNavigation(query = "") {
    cancelNavigationDrag();
    const container = document.querySelector("#primary-nav");
    if (!container) {
        return;
    }
    const normalizedQuery = String(query).trim().toLocaleLowerCase("zh-CN");
    const moduleItems = sortModules(state.modules, state.modulePreferences.sidebarOrder)
        .filter(isModuleVisible)
        .map((module) => moduleNavigationItem(module));
    const buildItems = pendingModuleBuilds(state.moduleBuilds, state.modules)
        .map((entry) => moduleBuildNavigationItem(entry));
    const sections = [
        {
            ...NAV_SECTIONS[0],
            items: [...NAV_SECTIONS[0].items, ...moduleItems, ...buildItems]
        },
        NAV_SECTIONS[1]
    ];
    container.replaceChildren();

    sections.forEach((section) => {
        const visibleItems = section.items.filter((item) => {
            const haystack = `${item.label} ${item.caption} ${item.buildStatus ?? ""}`
                .toLocaleLowerCase("zh-CN");
            return !normalizedQuery || haystack.includes(normalizedQuery);
        });
        if (!visibleItems.length) {
            return;
        }

        const group = element("section", "nav-group");
        group.append(element("h2", "nav-group__label", section.label));
        const list = element("div", "nav-list");
        visibleItems.forEach((item) => {
            const button = element(
                "button",
                `nav-item${state.activeView === item.id ? " is-active" : ""}`
            );
            button.type = "button";
            button.dataset.view = item.id;
            const count = item.moduleId ? navigationCount(item.moduleId) : null;
            const countLabel = count == null ? "" : `，数量 ${count}`;
            button.setAttribute(
                "aria-label",
                `${item.label}，${item.caption}${countLabel}`
            );
            if (state.activeView === item.id) {
                button.setAttribute("aria-current", "page");
            }
            if (item.moduleId) {
                button.classList.add("nav-item--module");
                button.dataset.moduleId = item.moduleId;
                button.title = "长按拖动调整顺序；Alt+↑/↓ 也可排序";
                button.setAttribute("aria-keyshortcuts", "Alt+ArrowUp Alt+ArrowDown");
            }
            if (item.buildSource) {
                button.classList.add("nav-item--build");
                button.dataset.buildStatus = item.buildStatus;
                button.title = `${item.caption}；${moduleBuildStatusLabel(item.buildStatus)}`;
            }
            button.innerHTML = `<span class="nav-icon">${icon(item.icon)}</span>`;
            if (item.moduleId) {
                const handle = element("span", "nav-drag-handle", "⋮");
                handle.setAttribute("aria-hidden", "true");
                button.prepend(handle);
            }
            const copy = element("span", "nav-copy");
            copy.append(element("strong", "", item.label));
            if (item.buildSource) {
                copy.append(element(
                    "small",
                    "nav-build-state",
                    moduleBuildStatusLabel(item.buildStatus)
                ));
            }
            button.append(copy);
            button.addEventListener("click", (event) => {
                if (performance.now() < suppressNavigationClickUntil) {
                    event.preventDefault();
                    return;
                }
                if (item.buildSource) {
                    void openModuleBuild(item.buildSource);
                } else {
                    void navigate(item.id);
                }
            });
            if (item.moduleId) {
                button.addEventListener("pointerdown", (event) => {
                    beginNavigationDrag(event, button, item.moduleId);
                });
                // 键盘排序只交换可见模块在宿主顺序中的相对位置，隐藏模块的槽位保持不变。
                button.addEventListener("keydown", (event) => {
                    if (!event.altKey || !["ArrowUp", "ArrowDown"].includes(event.key)) return;
                    event.preventDefault();
                    changeSidebarModuleOrder(item.moduleId, event.key === "ArrowUp" ? -1 : 1);
                });
            }
            list.append(button);
        });
        group.append(list);
        if (section.id === "workspace"
            && state.modules.length === 0
            && buildItems.length === 0) {
            group.append(element("p", "empty-state empty-state--nav", "尚未安装功能模块"));
        }
        container.append(group);
    });

    if (!container.childElementCount) {
        container.append(element("p", "empty-state", "没有匹配的功能"));
    }
    queueSidebarCountRender();
}

function beginNavigationDrag(event, button, moduleId) {
    if (event.button !== 0 || event.isPrimary === false || button.disabled) return;
    cancelNavigationDrag();
    suppressNavigationClickUntil = 0;
    navigationDrag = {
        active: false,
        after: false,
        element: button,
        moduleId,
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        targetId: null
    };
    navigationHoldTimer = window.setTimeout(activateNavigationDrag, NAV_DRAG_HOLD_MS);
}

// 长按超过阈值后才进入排序态；按住期间的移动超过容差会取消长按，从而保留普通点击。
function activateNavigationDrag() {
    navigationHoldTimer = null;
    if (!navigationDrag) return;
    navigationDrag.active = true;
    expandDesktopSidebarImmediately();
    navigationDrag.element.classList.add("is-nav-dragging");
    document.body.classList.add("is-navigation-dragging");
    try {
        navigationDrag.element.setPointerCapture(navigationDrag.pointerId);
    } catch {
        // 某些浏览器不支持指针捕获时仍可通过文档级 pointermove 完成排序。
    }
    const status = document.querySelector("#navigation-order-status");
    if (status) status.textContent = "正在调整模块顺序。";
}

function moveNavigationDrag(event) {
    if (!navigationDrag || event.pointerId !== navigationDrag.pointerId) return;
    const distance = Math.hypot(
        event.clientX - navigationDrag.startX,
        event.clientY - navigationDrag.startY
    );
    if (!navigationDrag.active) {
        if (distance > NAV_DRAG_MOVE_TOLERANCE) cancelNavigationDrag();
        return;
    }

    event.preventDefault();
    autoScrollNavigation(event.clientY);
    clearNavigationDropTargets();
    const target = document.elementFromPoint(event.clientX, event.clientY)
        ?.closest(".nav-item--module[data-module-id]");
    if (!target || target.dataset.moduleId === navigationDrag.moduleId) {
        navigationDrag.targetId = null;
        return;
    }
    const rect = target.getBoundingClientRect();
    navigationDrag.targetId = target.dataset.moduleId;
    navigationDrag.after = event.clientY > rect.top + (rect.height / 2);
    target.classList.add(navigationDrag.after ? "is-drop-after" : "is-drop-before");
}

function finishNavigationDrag(event) {
    if (!navigationDrag || event.pointerId !== navigationDrag.pointerId) return;
    if (!navigationDrag.active) {
        cancelNavigationDrag();
        return;
    }

    event.preventDefault();
    const { after, moduleId, targetId } = navigationDrag;
    const ids = state.modulePreferences.sidebarOrder;
    clearNavigationDrag();
    suppressNavigationClickUntil = performance.now() + 400;
    if (!targetId || targetId === moduleId) return;
    const reordered = reorderRelative(ids, moduleId, targetId, after);
    persistSidebarModuleOrder(reordered, "侧边栏模块顺序已更新。");
}

function cancelNavigationDrag(event) {
    if (event?.pointerId != null
        && navigationDrag
        && event.pointerId !== navigationDrag.pointerId) {
        return;
    }
    clearNavigationDrag();
}

// 排序可能由 pointerup、pointercancel、重渲染或页面隐藏结束，因此清理必须幂等，
// 并同时撤销指针捕获、drop 标记、全局拖动态与侧栏收起判定。
function clearNavigationDrag() {
    if (navigationHoldTimer != null) {
        window.clearTimeout(navigationHoldTimer);
        navigationHoldTimer = null;
    }
    clearNavigationDropTargets();
    if (navigationDrag) {
        navigationDrag.element.classList.remove("is-nav-dragging");
        try {
            navigationDrag.element.releasePointerCapture(navigationDrag.pointerId);
        } catch {
            // 指针已释放或浏览器不支持捕获时无后续处理。
        }
    }
    document.body.classList.remove("is-navigation-dragging");
    navigationDrag = null;
    reconcileDesktopSidebar();
}

function clearNavigationDropTargets() {
    document.querySelectorAll(".nav-item.is-drop-before, .nav-item.is-drop-after")
        .forEach((node) => node.classList.remove("is-drop-before", "is-drop-after"));
}

function autoScrollNavigation(clientY) {
    const scroller = document.querySelector(".sidebar-scroll");
    if (!scroller) return;
    const rect = scroller.getBoundingClientRect();
    const edge = 36;
    if (clientY < rect.top + edge) {
        scroller.scrollTop = Math.max(0, scroller.scrollTop - 14);
    } else if (clientY > rect.bottom - edge) {
        scroller.scrollTop += 14;
    }
}

function changeSidebarModuleOrder(moduleId, offset) {
    const orderIds = state.modulePreferences.sidebarOrder;
    const visibleIds = [...document.querySelectorAll(".nav-item--module[data-module-id]")]
        .map((node) => node.dataset.moduleId)
        .filter(Boolean);
    const ids = moveVisibleModuleOrder(
        orderIds,
        visibleIds,
        moduleId,
        offset
    );
    if (ids.length === orderIds.length && ids.every((id, index) => id === orderIds[index])) {
        return;
    }
    persistSidebarModuleOrder(ids, offset < 0 ? "模块已上移。" : "模块已下移。");
}

function persistSidebarModuleOrder(sidebarModuleOrderIds, message) {
    const previous = state.modulePreferences;
    const next = reconcileModulePreferences(state.modules, {
        ...previous,
        sidebarOrder: sidebarModuleOrderIds
    });
    state.modulePreferences = next;
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    const status = document.querySelector("#navigation-order-status");
    if (status) status.textContent = message;
    // 先乐观更新以保持拖动反馈；只有最后一次保存失败才回滚，避免较慢的旧请求覆盖新顺序。
    const generation = ++sidebarOrderRequestGeneration;
    void api.saveSidebarModuleOrder(next.sidebarOrder).catch((error) => {
        if (generation !== sidebarOrderRequestGeneration) {
            return;
        }
        state.modulePreferences = previous;
        renderNavigation(document.querySelector("#nav-search")?.value ?? "");
        toast(`侧边栏顺序保存失败：${errorMessage(error)}`, { error: true });
    });
}

/**
 * 把模块契约转换为导航项。未知模块不需要新增前端分支。
 *
 * @param {object} module 模块契约
 * @returns {object} 导航项
 */
function moduleNavigationItem(module) {
    const ui = state.moduleUi[module.moduleId];
    return {
        id: ui?.view.id ?? `module:${module.moduleId}`,
        label: module.title || module.moduleId,
        caption: ui?.view.caption ?? module.description ?? "扩展模块",
        icon: ui?.view.icon ?? "modules",
        moduleId: module.moduleId
    };
}

function moduleBuildNavigationItem(entry) {
    return {
        id: buildViewId(entry.sourceName),
        label: entry.title,
        caption: `${entry.caption} · ${moduleBuildStatusLabel(entry.status)}`,
        icon: entry.icon,
        buildSource: entry.sourceName,
        buildStatus: entry.status
    };
}

function moduleForView(view) {
    if (view === "overview" || view === "history") {
        return null;
    }
    return state.modules.find((module) =>
        isModuleVisible(module) && moduleNavigationItem(module).id === view) ?? null;
}

/**
 * 获取动态模块导航计数。只有有限正整数才生成外置数字气泡。
 *
 * @param {string} moduleId 业务或系统模块 id
 * @returns {number | null} 可显示数量
 */
function navigationCount(moduleId) {
    const id = String(moduleId ?? "").trim();
    if (!id) {
        return null;
    }
    const raw = state.moduleCounts[id];
    const numeric = typeof raw === "number"
        ? raw
        : typeof raw === "string" && /^\d+$/.test(raw.trim())
            ? Number(raw.trim())
            : Number.NaN;
    return Number.isInteger(numeric) && numeric > 0 ? numeric : null;
}

/**
 * 切换主视图。
 *
 * @param {string} view 视图名
 */
async function navigate(view) {
    state.activeView = view;
    syncHistoryViewRail();
    closeMobileNav();
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    const selectedModule = moduleForView(view);
    const buildSource = buildViewSource(view);
    const buildEntry = buildSource ? pendingBuildEntry(buildSource) : null;
    const labels = {
        overview: "总览",
        history: "历史记录"
    };
    const viewTitle = selectedModule?.title ?? buildEntry?.title ?? labels[view] ?? "模块";
    const mobileTitle = document.querySelector("#mobile-view-title");
    if (mobileTitle) {
        mobileTitle.textContent = viewTitle;
    }

    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.dataset.motionState = "loading";
    main.dataset.motionPhase = "active";
    main.innerHTML = `
        <section class="loading-panel" aria-live="polite">
            <span class="loading-orbit" aria-hidden="true"></span>
            <h1>正在读取本地状态</h1>
            <p>加载 ${selectedModule?.title ?? labels[view] ?? "工作台"}…</p>
        </section>
    `;

    try {
        if (view === "history") {
            await loadHistory();
        } else if (selectedModule) {
            await mountModule(selectedModule);
        } else if (buildSource) {
            renderModuleBuildView(buildSource);
        } else {
            await loadOverview();
        }
    } catch (error) {
        renderLoadError(error);
    }
    main.dataset.motionState = "view-change";
    main.dataset.motionPhase = "active";
    main.focus({ preventScroll: true });
}

/**
 * 渲染通用加载错误。
 *
 * @param {unknown} error 错误
 */
function renderLoadError(error) {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.dataset.motionState = "error";
    main.dataset.motionPhase = "active";
    const panel = element("section", "panel");
    panel.append(
        element("p", "eyebrow", "LOCAL SERVICE"),
        element("h1", "", "本地数据暂时不可用"),
        element("p", "", errorMessage(error)),
        element("p", "muted", "请确认 Java 宿主运行在 127.0.0.1:8080，然后刷新页面。")
    );
    main.replaceChildren(panel);
}

/**
 * 从模块 JAR 加载并挂载自有视图。
 *
 * 每次切换都会先建立独立容器并替换主内容区，再把有限宿主能力作为 context 注入。
 * 样式加载、动态 import、默认导出校验和 mount 调用共用当前模块的 try/catch，因此
 * 任一失败只替换这一个容器，不污染其他模块或框架级计划/历史状态。
 *
 * 副作用：向 DOM 添加模块样式和视图，执行受信任的模块脚本，并注册该脚本声明的监听器。
 *
 * @param {object} module 模块契约
 * @returns {Promise<void>}
 */
async function mountModule(module) {
    const ui = state.moduleUi[module.moduleId];
    if (!ui) {
        renderGenericModule(module, state.moduleErrors[module.moduleId] || "模块未提供 UI 清单");
        return;
    }
    const main = document.querySelector("#main-content");
    if (!main) return;

    const view = element("div", "view-stack module-view");
    view.dataset.moduleView = module.moduleId;
    const root = element("div", "module-view__root");
    root.id = `module-view-${module.moduleId}`;
    view.append(root);
    main.replaceChildren(view);

    try {
        const styleUrl = `${api.moduleAssetUrl(module.moduleId, ui.style)}?v=${state.moduleAssetVersion}`;
        await loadModuleStyle(module, styleUrl);
        const scriptUrl = `${api.moduleAssetUrl(module.moduleId, ui.script)}?v=${state.moduleAssetVersion}`;
        const moduleScript = await import(scriptUrl);
        if (typeof moduleScript.default !== "function") {
            throw new TypeError("模块 UI 必须默认导出 mount 函数");
        }
        const baseContext = {
            module,
            ui,
            query: (queryId, parameters = {}) => api.queryModule(
                module.moduleId,
                queryId,
                parameters
            ),
            executeAction: (actionId, argumentsValue = {}, confirmed = true) =>
                api.executeModuleAction(
                    module.moduleId,
                    actionId,
                    argumentsValue,
                    confirmed
                ),
            moduleDataUrl: (relativePath) => api.moduleDataUrl(module.moduleId, relativePath),
            executeActionWithFile: (actionId, argumentsValue, file, confirmed = true) =>
                api.executeModuleActionWithFile(
                    module.moduleId,
                    actionId,
                    argumentsValue,
                    file,
                    confirmed
                ),
            element,
            icon,
            toast,
            fillCommand,
            formatDate,
            subscribeEvents: subscribeFrameworkEvents
        };
        await moduleScript.default(root, createModuleMountContext({
            module,
            context: baseContext,
            settings: moduleSettings(module.moduleId),
            systemAdmin: systemAdminApi
        }));
    } catch (error) {
        renderModuleFailure(module, root, error);
    }
}

/**
 * 加载模块样式；样式加载失败也只影响当前模块。
 *
 * @param {object} module 模块契约
 * @param {string} url 样式地址
 */
function loadModuleStyle(module, url) {
    const loaded = [...document.querySelectorAll("link[data-orderly-module-style]")]
        .find((node) => node.dataset.orderlyModuleStyle === module.moduleId);
    if (loaded) {
        if (loaded.dataset.orderlyModuleUrl === url) {
            return Promise.resolve();
        }
        loaded.remove();
    }
    return new Promise((resolve, reject) => {
        const style = document.createElement("link");
        style.rel = "stylesheet";
        style.href = url;
        style.dataset.orderlyModuleStyle = module.moduleId;
        style.dataset.orderlyModuleUrl = url;
        style.addEventListener("load", () => resolve(), { once: true });
        style.addEventListener("error", () => {
            style.remove();
            reject(new TypeError(`模块样式加载失败：${url}`));
        }, { once: true });
        document.head.append(style);
    });
}

// 模块错误始终保持局部容器和可继续工作的说明，防止单个扩展把主机界面变成空白页。
function renderModuleFailure(module, container, error) {
    const panel = element("section", "panel module-local-error");
    panel.append(
        element("p", "eyebrow", "MODULE UI ERROR"),
        element("h1", "", `${module.title || module.moduleId} 界面加载失败`),
        element("p", "", errorMessage(error)),
        element("p", "muted", "此错误只影响当前模块；其他模块与计划执行仍可使用。")
    );
    container.replaceChildren(panel);
}

/**
 * 根据模块契约渲染通用详情页；模块 UI 缺失时使用此安全降级页。
 *
 * @param {object} module 模块契约
 * @param {string} localError 局部错误
 */
function renderGenericModule(module, localError = "") {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    const panel = element("section", "panel");
    const actions = Array.isArray(module.actions) ? module.actions : [];
    panel.append(
        element("p", "eyebrow", "MODULE CONTRACT"),
        element("h1", "", module.title || module.moduleId),
        element("p", "", module.description || "该模块未提供说明。"),
        element("p", "muted", `模块 ID：${module.moduleId} · ${actions.length} 个动作`)
    );
    if (localError) {
        panel.append(element("p", "module-local-error", `模块界面不可用：${localError}`));
    }

    const list = element("div", "data-list");
    if (actions.length === 0) {
        list.append(element("p", "empty-state", "模块契约没有声明动作。"));
    }
    actions.forEach((action) => {
        const card = element("article", "data-card");
        const header = element("header", "data-card__header");
        const copy = element("div");
        copy.append(
            element("strong", "", action.title || action.id || "未命名动作"),
            element("p", "muted", action.description || "未提供动作说明。")
        );
        header.append(copy, element("span", "status-chip", RISK_LABEL[action.risk] ?? action.risk ?? "未知"));
        card.append(header);

        const parameters = Array.isArray(action.parameters) ? action.parameters : [];
        const parameterText = parameters.length
            ? parameters.map((parameter) => `${parameter.name}:${parameter.type}${parameter.required ? "（必填）" : ""}`).join(" · ")
            : "无参数";
        card.append(
            element("p", "muted", `动作 ID：${action.id || "未声明"} · ${parameterText}`),
            element("p", "muted", action.confirmationRequired
                ? `执行前需要计划确认${action.confirmationPhrase ? `，确认词：${action.confirmationPhrase}` : ""}。`
                : "只读动作。")
        );
        list.append(card);
    });
    panel.append(list);
    main.replaceChildren(panel);
}

/** 加载总览和模块声明的摘要查询。 */
async function loadOverview() {
    await loadOverviewCounts();
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    renderOverview();
}

/**
 * 渲染总览结构。
 */
function renderOverview() {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    const planningCopy = state.planningEnabled
        ? `
            <p>
                计划功能已开启。下方计划预览仍使用本地规则计划器；
                右下角 AI 对话框当前仅演示界面，尚未接入真实 AI。
            </p>
            <div class="hero-tags">
                <span>${icon("shield")} 本地优先</span>
                <span>${icon("route")} 计划先于执行</span>
                <span>${icon("spark")} AI 对话框仅演示</span>
            </div>
        `
        : `
            <p>
                计划功能当前由系统设置关闭。已安装模块与历史记录仍可正常使用，
                需要生成和执行计划时可在系统设置中重新开启。
            </p>
            <div class="hero-tags">
                <span>${icon("shield")} 本地优先</span>
                <span>${icon("modules")} 模块契约协作</span>
                <span>${icon("history")} 历史记录保留</span>
            </div>
        `;
    const planningRegions = state.planningEnabled
        ? `
            <div class="workflow-grid" data-planning-region="plan">
                <section class="panel">
                    <header class="panel__header">
                        <div>
                            <p class="eyebrow">PLAN PREVIEW</p>
                            <h2>计划预览</h2>
                            <p>使用本地规则计划器生成计划；确认之前不会产生真实变更。</p>
                        </div>
                        <span class="status-chip" id="plan-mode-chip">等待指令</span>
                    </header>
                    <div id="plan-slot"></div>
                </section>
                <aside class="panel">
                    <header class="panel__header">
                        <div>
                            <p class="eyebrow">TRUST LAYER</p>
                            <h2>执行担保</h2>
                        </div>
                        <span class="status-chip status-chip--safe">在线</span>
                    </header>
                    <ul class="assumption-list">
                        <li>写入计划需要明确勾选确认。</li>
                        <li>删除还需键入“删除”，且只会移入项目回收站。</li>
                        <li>中途失败时，宿主会逆序回滚已经完成的步骤。</li>
                        <li>旧版计划预览与独立 AI 计划分离；右下角 AI 对话框连接当前进程内配置的模型。</li>
                    </ul>
                </aside>
            </div>

            <div class="workflow-grid" data-planning-region="execution">
                <section class="panel">
                    <header class="panel__header">
                        <div>
                            <p class="eyebrow">EXECUTION TRACE</p>
                            <h2>执行时间线</h2>
                            <p>状态来自宿主事件流，不使用模拟完成动画。</p>
                        </div>
                        <span class="status-chip" id="timeline-count">0 个事件</span>
                    </header>
                    <ol class="timeline" id="timeline"></ol>
                </section>
                <aside class="panel">
                    <header class="panel__header">
                        <div>
                            <p class="eyebrow">RESULT</p>
                            <h2>结果摘要</h2>
                        </div>
                    </header>
                    <div class="run-summary" id="run-summary"></div>
                </aside>
            </div>
        `
        : "";
    main.innerHTML = `
        <div class="view-stack" data-motion-scope="overview" data-motion-state="entering" data-motion-phase="active">
            <section class="hero-panel">
                <div class="hero-content">
                    <p class="eyebrow">CONTROLLED AGENT WORKFLOW</p>
                    <h1>让 AI 把事做完，<br><em>也让你随时踩刹车。</em></h1>
                    ${planningCopy}
                </div>
                <div class="hero-diagram" aria-label="已安装模块汇聚到归序">
                    <span class="orbit-ring"></span>
                    <span class="orbit-core"><strong>归序</strong><small>工作流中枢</small></span>
                    <span class="orbit-module-nodes" id="orbit-module-nodes"></span>
                </div>
            </section>

            <section class="surface-grid" id="module-card-grid" aria-label="已安装模块摘要"></section>
            <section class="module-build-strip" id="module-build-strip" aria-label="待构建模块" hidden></section>
            <p class="sr-only" id="module-order-status" aria-live="polite"></p>

            ${planningRegions}
        </div>
    `;

    hydrateIcons(main);
    renderModuleCards();
    renderModuleBuildOverview();
    renderPlan();
    renderTimeline();
    renderRunSummary();
}

function renderModuleBuildOverview() {
    const strip = document.querySelector("#module-build-strip");
    if (!strip) {
        return;
    }
    const entries = pendingModuleBuilds(state.moduleBuilds, state.modules);
    strip.replaceChildren();
    strip.hidden = entries.length === 0;
    if (entries.length === 0) {
        return;
    }
    const header = element("header", "module-build-strip__header");
    header.append(
        element("div", "module-build-strip__copy"),
        element("span", "status-chip", `${entries.length} 个模块准备中`)
    );
    header.firstElementChild.append(
        element("p", "eyebrow", "MODULE PIPELINE"),
        element("h2", "", "框架已可用，业务模块正在后台就绪"),
        element("p", "", "点击任一模块可请求优先构建；未加载模块不会提供动作或查询。")
    );
    const list = element("div", "module-build-strip__list");
    entries.forEach((entry) => {
        const button = element("button", "module-build-item");
        button.type = "button";
        button.dataset.buildStatus = entry.status;
        const iconNode = element("span", "module-build-item__icon");
        iconNode.innerHTML = icon(entry.icon, 18);
        const copy = element("span", "module-build-item__copy");
        copy.append(
            element("strong", "", entry.title),
            element("small", "", moduleBuildStatusLabel(entry.status))
        );
        button.append(iconNode, copy);
        button.addEventListener("click", () => void openModuleBuild(entry.sourceName));
        list.append(button);
    });
    strip.append(header, list);
}

async function loadOverviewCounts() {
    const jobs = state.modules.filter(isModuleVisible).map(async (module) => {
        const ui = state.moduleUi[module.moduleId];
        if (!ui?.card?.queryId) return [module.moduleId, null];
        try {
            const payload = await api.queryModule(module.moduleId, ui.card.queryId);
            return [module.moduleId, readCount(payload, ui.card.countKey)];
        } catch {
            return [module.moduleId, "—"];
        }
    });
    state.moduleCounts = Object.fromEntries(await Promise.all(jobs));
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        renderModuleCards();
        renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    }
}

function renderModuleCards() {
    const grid = document.querySelector("#module-card-grid");
    const orbit = document.querySelector("#orbit-module-nodes");
    if (!grid) return;
    const settings = settingsStore.getSnapshot();
    const ordered = sortModules(state.modules, settings.moduleOrderIds);
    const visible = ordered.filter(isModuleVisible);
    const displayed = visible.filter((module) =>
        state.moduleUi[module.moduleId]?.card !== null);
    grid.replaceChildren();
    if (orbit) {
        orbit.replaceChildren();
        displayed.slice(0, 3).forEach((module, index) => {
            const ui = state.moduleUi[module.moduleId];
            const node = element("span", `orbit-node orbit-node--slot-${index + 1}`, "");
            node.innerHTML = icon(ui?.view.icon ?? "modules", 22);
            orbit.append(node);
        });
    }
    if (!displayed.length) {
        const pendingBuilds = pendingModuleBuilds(state.moduleBuilds, state.modules);
        grid.append(element(
            "div",
            "panel empty-state module-empty-state",
            pendingBuilds.length
                ? "业务模块正在后台构建；可先从左侧选择模块并请求优先构建。"
                : state.modules.length
                ? "当前没有已启用的功能模块；可在模组设置中重新启用。"
                : "尚未安装功能模块；添加 Module/module_* 并重新构建后会显示在这里。"
        ));
        return;
    }

    // 总览顺序是浏览器本地设置，和宿主维护的侧边栏顺序相互独立；拖放与按钮最终都
    // 只调用 persistModuleOrder，避免同一顺序出现两套持久化路径。
    displayed.forEach((module, index) => {
        const ui = state.moduleUi[module.moduleId];
        const card = ui?.card ?? {
            title: module.title || module.moduleId,
            description: state.moduleErrors[module.moduleId] || module.description || "模块摘要不可用。",
            badge: "模块界面异常",
            icon: ui?.view.icon ?? "modules",
            unit: "项"
        };
        const article = element("article", "surface-card module-card");
        article.dataset.motionScope = "module-card";
        article.dataset.motionState = "entering";
        article.dataset.motionPhase = "active";
        article.draggable = true;
        article.dataset.moduleId = module.moduleId;
        article.tabIndex = 0;
        article.setAttribute("aria-label", `${card.title}，可拖动或使用按钮调整顺序`);
        const top = element("div", "surface-card__top");
        const cardIcon = element("span", "surface-card__icon");
        cardIcon.innerHTML = icon(card.icon, 21);
        top.append(cardIcon, element("span", "status-chip", card.badge));
        const body = element("div");
        const metric = element("div", "surface-card__metric");
        const value = state.moduleCounts[module.moduleId];
        metric.append(
            element("strong", "", value == null ? "—" : String(value)),
            element("small", "", card.unit)
        );
        body.append(metric, element("h2", "", card.title), element("p", "", card.description));
        const controls = element("div", "module-order-controls");
        const up = element("button", "icon-button", "↑");
        up.type = "button";
        up.disabled = index === 0;
        up.setAttribute("aria-label", `上移 ${card.title}`);
        up.addEventListener("click", () => changeModuleOrder(module.moduleId, -1));
        const down = element("button", "icon-button", "↓");
        down.type = "button";
        down.disabled = index === displayed.length - 1;
        down.setAttribute("aria-label", `下移 ${card.title}`);
        down.addEventListener("click", () => changeModuleOrder(module.moduleId, 1));
        controls.append(up, down);
        article.append(top, body, controls);
        article.addEventListener("dragstart", (event) => {
            state.dragModuleId = module.moduleId;
            article.dataset.motionState = "reordering";
            article.classList.add("is-dragging");
            event.dataTransfer?.setData("text/plain", module.moduleId);
            if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
        });
        article.addEventListener("dragover", (event) => {
            if (state.dragModuleId && state.dragModuleId !== module.moduleId) {
                event.preventDefault();
                article.classList.add("is-drop-target");
            }
        });
        article.addEventListener("dragleave", () => article.classList.remove("is-drop-target"));
        article.addEventListener("drop", (event) => {
            event.preventDefault();
            const movingId = event.dataTransfer?.getData("text/plain") || state.dragModuleId;
            article.classList.remove("is-drop-target");
            if (!movingId) return;
            const ids = reorderBefore(settingsStore.getSnapshot().moduleOrderIds, movingId, module.moduleId);
            persistModuleOrder(ids, "模块卡片顺序已更新。");
        });
        article.addEventListener("dragend", () => {
            state.dragModuleId = null;
            document.querySelectorAll(".module-card").forEach((node) => {
                node.dataset.motionState = "entering";
                node.classList.remove("is-dragging", "is-drop-target");
            });
        });
        grid.append(article);
    });
}

function changeModuleOrder(moduleId, offset) {
    const ids = moveModuleOrder(settingsStore.getSnapshot().moduleOrderIds, moduleId, offset);
    persistModuleOrder(ids, offset < 0 ? "模块已上移。" : "模块已下移。");
}

function persistModuleOrder(moduleOrderIds, message) {
    settingsStore.update({ moduleOrderIds });
    const status = document.querySelector("#module-order-status");
    if (status) status.textContent = message;
}

/**
 * 渲染旧版计划预览与确认控件。
 *
 * 这里只把已归一化的宿主计划投射为界面，不执行动作。确认门槛由
 * validateConfirmation 决定，执行中或已处理状态另行禁用；删除额外要求精确确认词，
 * 且执行请求仍由宿主再次担保。
 */
function renderPlan() {
    const slot = document.querySelector("#plan-slot");
    if (!slot) {
        return;
    }
    const chip = document.querySelector("#plan-mode-chip");
    slot.replaceChildren();

    if (!state.plan) {
        if (chip) {
            chip.textContent = "等待指令";
        }
        const empty = element("div", "plan-empty");
        const signal = element("span", "plan-empty__signal");
        signal.innerHTML = icon("route", 24);
        const examples = state.modules
            .filter(isModuleVisible)
            .flatMap((module) => state.moduleUi[module.moduleId]?.examples ?? []);
        empty.append(
            signal,
            element("h3", "", "使用本地规则计划器"),
            element(
                "p",
                "",
                examples.length
                    ? "输入已安装模块提供的示例指令。宿主会先返回步骤与风险，不会立即执行。"
                    : "当前没有已安装模块提供可用指令；添加 Module/module_* 并重新构建后再试。"
            )
        );

        const form = element("form", "plan-launch__form");
        const examplesBar = element("div", "plan-launch__examples");
        examples.slice(0, 4).forEach((example) => {
            const button = element("button", "example-chip", example.label);
            button.type = "button";
            button.title = example.text;
            button.addEventListener("click", () => {
                input.value = example.text;
                input.focus();
                input.setSelectionRange(input.value.length, input.value.length);
            });
            examplesBar.append(button);
        });
        const input = document.createElement("textarea");
        input.id = "plan-goal-input";
        input.rows = 1;
        input.maxLength = 600;
        input.setAttribute("aria-label", "计划目标");
        input.placeholder = examples.length
            ? `例如：${examples[0].text}`
            : "请输入已安装模块提供的示例指令";
        const row = element("div", "plan-launch__row");
        const submit = element("button", "primary-button", "生成计划");
        submit.type = "submit";
        submit.id = "plan-goal-submit";
        row.append(input, submit);
        const note = element(
            "p",
            "plan-launch__note",
            "本地规则计划器 · 未连接外部模型"
        );
        note.id = "plan-goal-note";
        form.append(...(examples.length ? [examplesBar] : []), row, note);
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            void draftGoal(input.value);
        });
        input.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                form.requestSubmit();
            }
        });
        empty.append(form);
        slot.append(empty);
        return;
    }

    if (chip) {
        chip.textContent = state.plan.kind === "QUESTION" ? "需要澄清" : `${state.plan.steps.length} 个步骤`;
    }
    const goal = element("div", "plan-goal");
    goal.append(
        element("strong", "", "目标复述"),
        element("p", "", state.plan.goal || "未提供目标复述")
    );
    slot.append(goal);

    if (state.plan.kind === "QUESTION") {
        const question = element("div", "plan-empty");
        question.append(
            element("h3", "", "需要补充信息"),
            element(
                "p",
                "",
                state.plan.message
                || "当前指令无法生成可验证计划，请换用界面中的示例指令。"
            )
        );
        slot.append(question);
        return;
    }

    // 风险展示取整条计划的上限；只读、写入、删除共用同一执行入口，但确认门槛不同。
    const deleteRisk = requiresDeletePhrase(state.plan);
    const writeRisk = state.plan.steps.some((step) => step.risk !== "READ_ONLY");
    const metaGrid = element("div", "plan-meta-grid");
    [
        ["风险", deleteRisk ? "含删除" : writeRisk ? "含写入" : "仅只读"],
        ["影响", state.plan.impact || "以实际执行结果为准"],
        ["置信度", state.plan.confidence > 0
            ? `${Math.round(state.plan.confidence * 100)}%`
            : "规则匹配"]
    ].forEach(([label, value]) => {
        const meta = element("div", "plan-meta");
        meta.append(element("span", "", label), element("strong", "", value));
        metaGrid.append(meta);
    });
    slot.append(metaGrid);

    if (state.plan.assumptions.length) {
        const assumptions = element("ul", "assumption-list");
        state.plan.assumptions.forEach((item) => {
            assumptions.append(element("li", "", item));
        });
        slot.append(assumptions);
    }

    const stepList = element("ol", "step-list");
    state.plan.steps.forEach((step, index) => {
        const row = element("li", "plan-step");
        row.append(element("span", "plan-step__index", String(index + 1).padStart(2, "0")));
        const copy = element("span", "plan-step__copy");
        copy.append(
            element("strong", "", step.title),
            element(
                "small",
                "",
                `${step.moduleId || "framework"} · ${step.actionId || "validated"}`
            )
        );
        const risk = step.risk in RISK_LABEL ? step.risk : "READ_ONLY";
        const chipNode = element("span", "risk-chip", RISK_LABEL[risk]);
        chipNode.dataset.risk = risk;
        row.append(copy, chipNode);
        stepList.append(row);
    });
    slot.append(stepList);

    const confirm = element("div", "plan-confirm");
    const planFinished = Boolean(
        state.currentRun
        && state.currentRun.planId === state.plan.planId
        && state.currentRun.status !== "RUNNING"
    );
    const check = element("label", "checkbox");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.confirmed;
    checkbox.disabled = state.executing || planFinished;
    check.append(checkbox, document.createTextNode("我已检查目标、步骤、假设与影响范围"));

    let deleteInput = null;
    if (deleteRisk) {
        const guard = element("div", "delete-confirm");
        guard.append(
            element("strong", "", "高破坏性操作"),
            element(
                "p",
                "",
                "文件不会物理删除；宿主会把它移入项目回收站。请输入“删除”继续。"
            )
        );
        deleteInput = document.createElement("input");
        deleteInput.type = "text";
        deleteInput.autocomplete = "off";
        deleteInput.value = state.deletePhrase;
        deleteInput.placeholder = "键入“删除”";
        deleteInput.disabled = state.executing || planFinished;
        guard.append(deleteInput);
        confirm.append(check, guard);
    } else {
        confirm.append(check);
    }

    const actions = element("div", "confirm-row");
    actions.append(element(
        "small",
        "muted",
        writeRisk ? "写入步骤将在确认后依次执行。" : "只读计划仍需确认后进入统一执行管线。"
    ));
    const button = element(
        "button",
        "primary-button",
        state.executing
            ? "正在执行…"
            : planFinished
                ? "本次计划已处理"
                : "确认并执行"
    );
    button.type = "button";
    button.disabled = state.executing || planFinished || !validateConfirmation(
        state.plan,
        { confirmed: state.confirmed, phrase: state.deletePhrase }
    ).allowed;
    actions.append(button);
    confirm.append(actions);
    slot.append(confirm);

    // 控件状态镜像到 state，用于重渲染后保持勾选与删除确认词；服务端校验不可省略。
    const sync = () => {
        state.confirmed = checkbox.checked;
        state.deletePhrase = deleteInput?.value ?? "";
        button.disabled = state.executing || planFinished || !validateConfirmation(
            state.plan,
            { confirmed: state.confirmed, phrase: state.deletePhrase }
        ).allowed;
    };
    checkbox.addEventListener("change", sync);
    deleteInput?.addEventListener("input", sync);
    button.addEventListener("click", () => void executePlan());
}

/**
 * 生成计划，不执行。
 *
 * @param {string} goal 用户目标
 */
async function draftGoal(goal) {
    if (!state.planningEnabled) {
        toast("计划功能已关闭，请在系统设置中开启。", { error: true });
        return;
    }
    const cleanGoal = String(goal ?? "").trim();
    if (!cleanGoal) {
        toast("请先输入一条工作流目标。");
        document.querySelector("#plan-goal-input")?.focus();
        return;
    }
    if (state.planBusy) {
        return;
    }

    state.planBusy = true;
    document.querySelector(".app-shell")?.setAttribute("data-motion-state", "busy");
    const send = document.querySelector("#plan-goal-submit");
    const note = document.querySelector("#plan-goal-note");
    if (send) {
        send.disabled = true;
    }
    if (note) {
        note.textContent = "正在校验目标并生成结构化计划…";
    }
    try {
        const payload = await api.draftPlan(cleanGoal);
        state.plan = normalizePlan(payload);
        state.confirmed = false;
        state.deletePhrase = "";
        state.currentRun = null;
        state.executing = false;
        await navigate("overview");
        pulsePlanModules();
        toast(state.plan.kind === "QUESTION"
            ? "计划器需要补充信息，未执行任何操作。"
            : `已生成 ${state.plan.steps.length} 步计划，确认前不会执行。`);
    } catch (error) {
        toast(errorMessage(error), { error: true });
    } finally {
        state.planBusy = false;
        document.querySelector(".app-shell")?.setAttribute("data-motion-state", "ready");
        if (send) {
            send.disabled = false;
        }
        if (note) {
            note.textContent = "未连接外部模型；当前使用可审计的本地规则计划器。";
        }
    }
}

/**
 * 执行用户已确认的旧版计划。
 *
 * 客户端校验只负责即时反馈，真正的契约、风险、快照和回滚仍由宿主承担。执行期间
 * 禁止重复提交，并先显示 RUNNING 摘要；失败时保留失败摘要而不是伪造成完成状态。
 *
 * 副作用：更新计划/执行状态、请求宿主执行和事件、重绘总览与结果区域。
 */
async function executePlan() {
    if (!state.planningEnabled || !state.plan || state.executing) {
        return;
    }
    const decision = validateConfirmation(state.plan, {
        confirmed: state.confirmed,
        phrase: state.deletePhrase
    });
    if (!decision.allowed) {
        toast(decision.reason);
        return;
    }

    state.executing = true;
    syncExitTrigger();
    const shell = document.querySelector(".app-shell");
    shell?.setAttribute("data-motion-state", "busy");
    state.currentRun = {
        planId: state.plan.planId,
        executionId: "",
        origin: EVENT_ORIGIN.PLAN,
        status: "RUNNING",
        message: "宿主正在执行并记录事件"
    };
    renderPlan();
    renderRunSummary();
    try {
        const payload = await api.confirmPlan(state.plan.planId, {
            confirmed: true,
            confirmation: requiresDeletePhrase(state.plan) ? state.deletePhrase : ""
        });
        applyRunPayload(payload);
        const latest = await api.getEvents().catch(() => []);
        state.events = mergeEvents(state.events, unwrapList(latest, ["events"]));
        applyRunPayload(payload);
        toast(state.currentRun?.status === "COMPLETED"
            ? "计划已执行完成，可在结果摘要中撤销。"
            : state.currentRun?.message || "执行返回了非完成状态。",
        { error: state.currentRun?.status !== "COMPLETED" });
    } catch (error) {
        state.currentRun = {
            planId: state.plan.planId,
            executionId: "",
            origin: EVENT_ORIGIN.PLAN,
            status: "FAILED",
            message: errorMessage(error),
            canRollback: false
        };
        toast(errorMessage(error), { error: true });
    } finally {
        state.executing = false;
        syncExitTrigger();
        shell?.setAttribute("data-motion-state", "ready");
        renderPlan();
        renderTimeline();
        renderRunSummary();
        void loadOverviewCounts();
    }
}

/**
 * 合并执行返回和事件状态。
 *
 * @param {unknown} payload 执行响应
 */
function applyRunPayload(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    const returnedEvents = unwrapList(source, ["events"]);
    state.events = mergeEvents(state.events, returnedEvents);
    const status = String(source.status ?? "COMPLETED").toUpperCase();
    state.currentRun = {
        planId: String(source.planId ?? state.plan?.planId ?? ""),
        executionId: String(source.executionId ?? state.currentRun?.executionId ?? ""),
        origin: inferEventOrigin(source),
        status,
        message: String(source.message ?? source.summary ?? statusLabel(status)),
        canRollback: Boolean(source.canRollback ?? ["COMPLETED", "PARTIAL"].includes(status)),
        result: source.result ?? source.output ?? null
    };
    applyEventStatuses();
    renderTimeline();
    renderRunSummary();
    renderPlan();
}

/**
 * 将当前计划范围内的最新事件状态同步回计划步骤。
 *
 * 事件可能乱序或重复到达，mergeEvents 已按序号去重排序；这里只取每个步骤的最新事件，
 * 不根据最终执行摘要猜测未发生步骤的状态。
 */
function applyEventStatuses() {
    if (!state.plan) {
        return;
    }
    const planEvents = planScopedEvents();
    for (const step of state.plan.steps) {
        const matching = planEvents.filter((event) => (
            event.stepId === step.id
            || event.stepId === String(step.id)
        ));
        const latest = matching.at(-1);
        if (latest) {
            step.status = latest.status;
        }
    }
}

/**
 * 当前计划相关事件。
 *
 * @returns {Array<object>} 事件
 */
function planScopedEvents() {
    const planId = state.plan?.planId || state.currentRun?.planId || "";
    if (!planId) {
        return [];
    }
    return state.events.filter((event) => !event.planId || event.planId === planId);
}

/**
 * 把执行事件状态映射为稳定的反馈语义状态。
 *
 * @param {unknown} status 事件状态
 * @returns {string} 语义状态
 */
function motionStateForStatus(status) {
    const normalized = String(status ?? "").toUpperCase();
    if (["RUNNING", "PENDING", "WAITING", "EXECUTING"].includes(normalized)) {
        return "running";
    }
    if (["COMPLETED", "SUCCESS"].includes(normalized)) {
        return "success";
    }
    if (["FAILED", "REJECTED"].includes(normalized)) {
        return "error";
    }
    if (["ROLLED_BACK", "ROLLBACK"].includes(normalized)) {
        return "warning";
    }
    return "info";
}

/**
 * 渲染事件或执行来源标签。
 *
 * @param {string} origin 来源
 * @returns {HTMLElement} 标签
 */
function originChip(origin) {
    const normalized = String(origin ?? EVENT_ORIGIN.SYSTEM).toUpperCase();
    const chip = element(
        "span",
        "origin-chip",
        EVENT_ORIGIN_LABEL[normalized] ?? EVENT_ORIGIN_LABEL[EVENT_ORIGIN.SYSTEM]
    );
    chip.dataset.origin = normalized;
    return chip;
}

/**
 * 渲染真实执行时间线。
 */
function renderTimeline() {
    const timeline = document.querySelector("#timeline");
    if (!timeline) {
        return;
    }
    const events = planScopedEvents().slice(-30);
    const count = document.querySelector("#timeline-count");
    if (count) {
        count.textContent = `${events.length} 个事件`;
    }
    timeline.replaceChildren();
    if (!events.length) {
        const empty = element("li", "empty-state");
        empty.append(
            element("strong", "", "尚无执行事件"),
            element("p", "", "确认计划后，宿主推送的每一步输入、状态与结果会显示在这里。")
        );
        timeline.append(empty);
        return;
    }

    events.forEach((event, index) => {
        const item = element("li", "timeline-item");
        item.dataset.status = event.status;
        item.dataset.motionScope = "feedback";
        item.dataset.motionState = motionStateForStatus(event.status);
        item.dataset.motionPhase = "active";
        item.append(element(
            "span",
            "timeline-marker",
            String(event.sequence ?? index + 1).slice(-3)
        ));
        const copy = element("span", "timeline-copy");
        const heading = element("span", "timeline-heading");
        heading.append(
            element("strong", "", statusLabel(event.status)),
            originChip(event.origin)
        );
        copy.append(
            heading,
            element("p", "", event.message || event.title)
        );
        item.append(copy);
        item.append(element(
            "span",
            "timeline-time",
            event.timestamp ? formatDate(event.timestamp) : "实时"
        ));
        timeline.append(item);
    });
}

/**
 * 渲染当前执行结果。
 */
function renderRunSummary() {
    const summary = document.querySelector("#run-summary");
    if (!summary) {
        return;
    }
    summary.replaceChildren();
    if (!state.currentRun) {
        summary.append(
            element("strong", "", "等待计划执行"),
            element("p", "muted", "执行完成后，这里会显示状态、事件统计和撤销入口。")
        );
        return;
    }

    const run = state.currentRun;
    const status = run.status.toUpperCase();
    const banner = element(
        "div",
        `summary-banner${status === "ROLLED_BACK" ? " is-rolled-back" : ""}${["FAILED", "REJECTED"].includes(status) ? " is-failed" : ""}`
    );
    banner.append(
        element("span", "", statusLabel(status)),
        element("strong", "", run.message)
    );
    summary.append(banner);

    const scoped = planScopedEvents();
    const stats = element("div", "summary-stats");
    [
        ["成功", scoped.filter((event) => ["COMPLETED", "SUCCESS"].includes(event.status)).length],
        ["异常", scoped.filter((event) => ["FAILED", "REJECTED"].includes(event.status)).length],
        ["事件", scoped.length]
    ].forEach(([label, value]) => {
        const stat = element("div", "summary-stat");
        stat.append(element("span", "", label), element("strong", "", String(value)));
        stats.append(stat);
    });
    summary.append(stats);

    if (["COMPLETED", "PARTIAL", "SUCCESS", "SUCCEEDED"].includes(status) && run.executionId) {
        const rollback = element("button", "danger-button", "回滚本次执行");
        rollback.type = "button";
        rollback.addEventListener("click", () => void rollbackCurrent());
        summary.append(rollback);
    }
}

/**
 * 回滚当前任务。
 */
async function rollbackCurrent() {
    const executionId = state.currentRun?.executionId ?? "";
    if (!executionId) {
        toast("执行记录 ID 尚未返回，暂时无法回滚。", { error: true });
        return;
    }
    try {
        const payload = await api.rollbackExecution(executionId);
        applyRunPayload({
            ...(payload && typeof payload === "object" ? payload : {}),
            executionId,
            status: payload && typeof payload === "object"
                ? payload.status ?? "ROLLED_BACK"
                : "ROLLED_BACK",
            message: payload && typeof payload === "object"
                ? payload.message ?? "任务已逆序回滚"
                : "任务已逆序回滚"
        });
        toast("任务已回滚，原始执行事件仍保留。");
        void loadOverviewCounts();
    } catch (error) {
        toast(errorMessage(error), { error: true });
    }
}

/**
 * 将计划涉及的模块在侧边栏短暂标记。
 */
function pulsePlanModules() {
    const modules = new Set(state.plan?.steps.map((step) => step.moduleId) ?? []);
    const sidebar = document.querySelector(".sidebar");
    clearSidebarAttention();
    if (sidebar && desktopSidebar.active) {
        sidebar.setAttribute("data-motion-state", "attention");
        sidebar.setAttribute("data-motion-phase", "active");
        desktopSidebar.attentionTimer = window.setTimeout(() => {
            desktopSidebar.attentionTimer = null;
            sidebar.setAttribute(
                "data-motion-state",
                desktopSidebar.expanded ? "expanded" : "collapsed"
            );
            sidebar.setAttribute("data-motion-phase", "active");
        }, 2600);
    }
    document.querySelectorAll("[data-view]").forEach((button) => {
        const target = Boolean(button.dataset.moduleId && modules.has(button.dataset.moduleId));
        button.classList.toggle("is-plan-target", target);
        if (target) {
            window.setTimeout(() => button.classList.remove("is-plan-target"), 2600);
        }
    });
}


/**
 * 加载历史事件与运行摘要。
 *
 * 三个来源独立请求：事件失败只影响时间线提示；执行记录失败时才兼容回退到旧 runs
 * 接口，并明确禁用缺少 executionId 的运行摘要回滚，避免把兼容记录当作可回滚事实。
 *
 * 副作用：覆盖历史相关 state 并重绘当前历史视图。
 */
async function loadHistory() {
    const [executionResult, runResult, eventResult] = await Promise.allSettled([
        api.getExecutions(),
        api.getRuns(),
        api.getEvents()
    ]);
    state.historyIssues = [];
    if (eventResult.status === "fulfilled") {
        state.events = mergeEvents(
            state.events,
            unwrapList(eventResult.value, ["events"])
        );
    } else {
        state.historyIssues.push({
            scope: "events",
            message: `事件读取失败：${errorMessage(eventResult.reason)}`
        });
    }

    let records = [];
    if (executionResult.status === "fulfilled") {
        state.historySource = "executions";
        records = unwrapList(executionResult.value, ["executions"])
            .filter((item) => item && typeof item === "object");
    } else if (runResult.status === "fulfilled") {
        state.historySource = "runs";
        records = unwrapList(runResult.value, ["runs"])
            .filter((item) => item && typeof item === "object");
        state.historyIssues.push({
            scope: "summary",
            message: `执行记录接口不可用，已回退到 /api/runs：${errorMessage(executionResult.reason)}`
        });
    } else {
        state.historySource = "";
        state.historyIssues.push({
            scope: "summary",
            message: "运行摘要来源暂时不可用。"
        });
    }
    state.historyRuns = records.map((record, index) => {
        const normalized = normalizeExecutionRecord(record, index);
        return state.historySource === "executions"
            ? normalized
            : { ...normalized, executionId: "" };
    });
    renderHistory();
}

/**
 * 渲染历史记录页及当前选择的视图。
 */
function renderHistory() {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    syncHistoryViewRail();
    const sourceLabel = state.historySource === "executions"
        ? "运行摘要读取自 /api/executions"
        : state.historySource === "runs"
            ? "执行记录接口不可用，运行摘要兼容读取自 /api/runs"
            : "运行摘要来源暂不可用";
    const issueMessages = state.historyIssues
        .filter((issue) => issue.scope === state.historyView
            || (state.historyView === "summary" && issue.scope === "summary")
            || (state.historyView === "timeline" && issue.scope === "events"))
        .map((issue) => issue.message);
    const issueNotice = issueMessages.length
        ? `<div class="history-notice">${issueMessages
            .map((message) => `<p>${escapeHtml(message)}</p>`)
            .join("")}</div>`
        : "";
    const viewMarkup = state.historyView === "summary"
        ? `
            <section class="panel">
                <header class="panel__header">
                    <div>
                        <p class="eyebrow">EXECUTION SUMMARY</p>
                        <h2>运行摘要</h2>
                        <p>${sourceLabel}</p>
                    </div>
                    <span class="status-chip">${state.historyRuns.length} 次执行</span>
                </header>
                ${issueNotice}
                <div id="run-list"></div>
            </section>
        `
        : `
            <section class="panel">
                <header class="panel__header">
                    <div>
                        <p class="eyebrow">EVENT TIMELINE</p>
                        <h2>事件时间线</h2>
                        <p>按宿主序号展示计划、AI 直接执行、模块控制与系统事件。</p>
                    </div>
                    <span class="status-chip">${state.events.length} 条</span>
                </header>
                ${issueNotice}
                <ol class="timeline" id="history-timeline"></ol>
            </section>
        `;
    main.innerHTML = `
        <div class="view-stack">
            <header class="page-heading">
                <span class="page-heading__icon">${icon("history", 25)}</span>
                <div>
                    <p class="eyebrow">LOCAL HISTORY</p>
                    <h1>历史记录</h1>
                    <p>审计事件与运行摘要均来自本地宿主；回滚不会删除原始历史。</p>
                </div>
                <span class="status-chip">${state.historyView === "timeline" ? "默认时间线" : "运行摘要"}</span>
            </header>
            ${viewMarkup}
        </div>
    `;
    hydrateIcons(main);
    if (state.historyView === "summary") {
        renderHistoryRuns(state.historyRuns);
    } else {
        renderHistoryEvents();
    }
}

/**
 * 渲染运行记录。
 *
 * @param {Array<object>} runs 运行列表
 */
function renderHistoryRuns(runs) {
    const container = document.querySelector("#run-list");
    if (!container) {
        return;
    }
    container.replaceChildren();
    if (!runs.length) {
        const summaryIssue = state.historyIssues
            .filter((issue) => issue.scope === "summary")
            .map((issue) => issue.message)
            .join(" ");
        container.append(emptyState(
            "尚无运行摘要",
            summaryIssue || "计划或 AI 直接执行完成后会在这里留下执行记录。"
        ));
        return;
    }
    const list = element("div", "run-list");
    runs.forEach((run) => {
        const status = String(run.status ?? "COMPLETED").toUpperCase();
        const row = element("article", "run-row");
        const copy = element("div");
        copy.append(
            element("h3", "", run.goal || "未命名执行")
        );
        const meta = element("div", "run-meta");
        meta.append(originChip(run.origin));
        meta.append(element(
            "span",
            "",
            `${statusLabel(status)} · ${formatDate(run.createdAt)} · ${run.displayId.slice(0, 24)}`
        ));
        copy.append(meta);
        if (run.message) {
            copy.append(element("p", "", run.message));
        }
        const actions = element("div", "run-actions");
        actions.append(element("span", `risk-chip`, statusLabel(status)));
        const decision = executionRollbackDecision(run);
        const button = element(
            "button",
            "text-button",
            state.rollbackBusyId === run.executionId ? "正在回滚…" : "回滚执行"
        );
        button.type = "button";
        button.disabled = !decision.allowed || Boolean(state.rollbackBusyId);
        button.title = decision.reason || "通过执行记录回滚本次变更";
        button.addEventListener("click", () => void rollbackHistoryExecution(run));
        actions.append(button);
        if (state.rollbackFailures[run.executionId]) {
            copy.append(element(
                "p",
                "inline-error",
                `回滚失败：${state.rollbackFailures[run.executionId]}`
            ));
        }
        row.append(copy, actions);
        list.append(row);
    });
    container.append(list);
}

/**
 * 判断执行记录是否可回滚。
 *
 * @param {object} run 执行记录
 * @returns {{allowed: boolean, reason: string}} 判定
 */
function executionRollbackDecision(run) {
    if (state.historySource !== "executions" || !run?.executionId) {
        return {
            allowed: false,
            reason: "当前兼容运行摘要没有执行记录 ID，无法调用执行回滚。"
        };
    }
    if (!ROLLBACK_ORIGINS.has(run.origin)) {
        return {
            allowed: false,
            reason: "仅计划与 AI 直接执行记录支持回滚。"
        };
    }
    if (!run.canRollback) {
        return {
            allowed: false,
            reason: run.rollbackDisabledReason || "宿主标记当前执行不可回滚。"
        };
    }
    if (state.rollbackFailures[run.executionId]) {
        return {
            allowed: false,
            reason: `上次回滚失败：${state.rollbackFailures[run.executionId]}`
        };
    }
    return { allowed: true, reason: "" };
}

/**
 * 通过执行记录端点回滚单次执行。
 *
 * @param {object} run 执行记录
 */
async function rollbackHistoryExecution(run) {
    const decision = executionRollbackDecision(run);
    if (!decision.allowed || state.rollbackBusyId) {
        if (decision.reason) {
            toast(decision.reason, { error: true });
        }
        return;
    }
    state.rollbackBusyId = run.executionId;
    renderHistory();
    try {
        await api.rollbackExecution(run.executionId);
        delete state.rollbackFailures[run.executionId];
        toast("执行记录已回滚，原始事件与历史仍保留。");
        state.rollbackBusyId = "";
        await loadHistory();
    } catch (error) {
        state.rollbackBusyId = "";
        state.rollbackFailures[run.executionId] = errorMessage(error);
        renderHistory();
        toast(`回滚失败：${errorMessage(error)}`, { error: true });
    }
}

/**
 * 渲染历史事件。
 */
function renderHistoryEvents() {
    const timeline = document.querySelector("#history-timeline");
    if (!timeline) {
        return;
    }
    const events = state.events.slice(-30);
    timeline.replaceChildren();
    if (!events.length) {
        timeline.append(emptyState("尚无事件", "当前没有可展示的执行审计。"));
        return;
    }
    events.forEach((event, index) => {
        const item = element("li", "timeline-item");
        item.dataset.status = event.status;
        item.dataset.motionScope = "feedback";
        item.dataset.motionState = motionStateForStatus(event.status);
        item.dataset.motionPhase = "active";
        item.append(element("span", "timeline-marker", String(event.sequence ?? index + 1).slice(-3)));
        const copy = element("span", "timeline-copy");
        const heading = element("span", "timeline-heading");
        heading.append(
            element("strong", "", statusLabel(event.status)),
            originChip(event.origin)
        );
        copy.append(
            heading,
            element("p", "", event.message || event.title)
        );
        item.append(copy, element(
            "span",
            "timeline-time",
            event.timestamp ? formatDate(event.timestamp) : "—"
        ));
        timeline.append(item);
    });
}

/**
 * 空状态片段。
 *
 * @param {string} title 标题
 * @param {string} text 描述
 * @returns {HTMLElement} 空状态
 */
function emptyState(title, text) {
    const node = element("div", "empty-state");
    node.append(element("strong", "", title), element("p", "", text));
    return node;
}

/**
 * 将示例填入指令栏。
 *
 * @param {string} command 指令
 */
function fillCommand(command) {
    if (!state.planningEnabled) {
        toast("计划功能已关闭，请在系统设置中开启。", { error: true });
        return;
    }
    const apply = () => {
        const input = document.querySelector("#plan-goal-input");
        if (!input) {
            return;
        }
        input.value = String(command ?? "");
        input.focus({ preventScroll: true });
        input.setSelectionRange(input.value.length, input.value.length);
        input.scrollIntoView({ block: "center" });
    };
    if (state.activeView !== "overview" || !document.querySelector("#plan-goal-input")) {
        void navigate("overview").then(() => window.requestAnimationFrame(apply));
        return;
    }
    window.requestAnimationFrame(apply);
}

/**
 * 连接宿主持久化执行事件流。
 *
 * 收到事件后先尝试驱动当前 AI 回合的模块调用提示，再合并审计数据并通知模块。
 * DOM 更新按动画帧合并，避免高频 SSE 造成多次重排。计划视图只消费当前计划事件；
 * 历史页则保留全部已归一化事件。
 *
 * 副作用：建立 EventSource 等价连接并持续写入 state.events；pagehide 时由全局入口关闭。
 */
function connectEventStream() {
    eventStream = api.streamEvents({
        onEvent: (raw) => {
            const event = normalizeEvent(raw, state.events.length);
            aiDialog?.handleInvocationEvent?.(event);
            state.events = mergeEvents(state.events, [event]);
            emitFrameworkEvent(event);
            if (event.planId && state.plan?.planId && event.planId !== state.plan.planId) {
                return;
            }
            if (!eventRenderQueued) {
                eventRenderQueued = true;
                window.requestAnimationFrame(() => {
                    eventRenderQueued = false;
                    applyEventStatuses();
                    renderTimeline();
                    renderHistoryEvents();
                });
            }
        },
        onError: () => {
            // EventSource 自带重连；连接错误不作为任务失败。
        }
    });
}

/**
 * 同步移动侧栏的键盘可交互状态。
 */
function syncMobileSidebarAvailability() {
    const sidebar = document.querySelector("#sidebar");
    if (!sidebar) {
        return;
    }
    const hidden = window.matchMedia(MOBILE_NAV_QUERY).matches
        && !sidebar.classList.contains("is-open");
    sidebar.inert = hidden;
    if (hidden) {
        sidebar.setAttribute("aria-hidden", "true");
    } else {
        sidebar.removeAttribute("aria-hidden");
    }
}

/**
 * 打开移动端侧边栏。
 */
function openMobileNav() {
    if (!window.matchMedia(MOBILE_NAV_QUERY).matches) {
        return;
    }
    clearSidebarCountLayer();
    const sidebar = document.querySelector(".sidebar");
    const menu = document.querySelector("#mobile-menu");
    sidebar?.classList.add("is-open");
    sidebar?.setAttribute("data-motion-state", "open");
    sidebar?.setAttribute("data-motion-phase", "active");
    menu?.setAttribute("aria-expanded", "true");
    syncMobileSidebarAvailability();
    const scrim = document.querySelector("#mobile-scrim");
    if (scrim) {
        scrim.hidden = false;
    }
    document.querySelector("#nav-search")?.focus({ preventScroll: true });
}

/**
 * 关闭移动端侧边栏。
 *
 * @param {{restoreFocus?: boolean}} options 焦点策略
 */
function closeMobileNav({ restoreFocus = true } = {}) {
    const sidebar = document.querySelector(".sidebar");
    const menu = document.querySelector("#mobile-menu");
    const wasOpen = sidebar?.classList.contains("is-open") ?? false;
    const focusWithinSidebar = Boolean(sidebar?.contains(document.activeElement));
    sidebar?.classList.remove("is-open");
    if (wasOpen) {
        sidebar?.setAttribute("data-motion-state", "collapsed");
        sidebar?.setAttribute("data-motion-phase", "active");
    }
    menu?.setAttribute("aria-expanded", "false");
    const scrim = document.querySelector("#mobile-scrim");
    if (scrim) {
        scrim.hidden = true;
    }
    if (wasOpen
        && restoreFocus
        && focusWithinSidebar
        && window.matchMedia(MOBILE_NAV_QUERY).matches) {
        menu?.focus({ preventScroll: true });
    }
    syncMobileSidebarAvailability();
    reconcileDesktopSidebar();
    queueSidebarCountRender();
}

/**
 * 打开设置。
 */
function openSettings() {
    const dialog = document.querySelector("#settings-dialog");
    if (dialog && !dialog.open) {
        renderSettings();
        dialog.dataset.motionState = "open";
        dialog.dataset.motionPhase = "active";
        dialog.showModal();
        reconcileDesktopSidebar();
    }
}

/**
 * 绑定设置弹窗的关闭、重置与快捷键。
 *
 * 使用原生 dialog 的模态焦点边界；关闭时先同步 DOM 状态，再在微任务中恢复触发按钮
 * 焦点和桌面侧栏收起判定，避免关闭动画期间焦点落到 inert 内容或抽屉外。
 */
function bindSettingsDialog() {
    const dialog = document.querySelector("#settings-dialog");
    const settingsTrigger = document.querySelector("#open-settings");
    const sidebar = document.querySelector("#sidebar");
    const closeDialog = () => {
        if (!dialog) {
            return;
        }
        dialog.dataset.motionState = "closing";
        dialog.dataset.motionPhase = "idle";
        dialog.close();
    };
    document.querySelector("#close-settings")?.addEventListener("click", closeDialog);
    document.querySelector("#reset-settings")?.addEventListener("click", () => {
        clearThemeSwitchAlert();
        settingsStore.reset();
        toast("设置已恢复默认。");
    });
    dialog?.addEventListener("click", (event) => {
        if (event.target === dialog) {
            closeDialog();
        }
    });
    dialog?.addEventListener("close", () => {
        dialog.dataset.motionState = "closed";
        dialog.dataset.motionPhase = "idle";
        if (sidebar?.classList.contains("is-open")) {
            window.queueMicrotask(() => {
                settingsTrigger?.focus({ preventScroll: true });
            });
        }
        window.queueMicrotask(reconcileDesktopSidebar);
    });
    // Ctrl/Cmd+, 是框架固定入口；单独注册，便于设置弹窗替换内容时不丢失快捷键。
    document.addEventListener("keydown", (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === ",") {
            event.preventDefault();
            openSettings();
        }
    });
}

/**
 * 渲染设置弹窗。
 */
function renderSettings({ focusPanel = false } = {}) {
    const nav = document.querySelector("#settings-nav");
    const panel = document.querySelector("#settings-panel");
    if (!nav || !panel) {
        return;
    }
    if (!["appearance", "ai", "system"].includes(state.settingsSection)) {
        state.settingsSection = "appearance";
    }
    const sections = [
        { id: "appearance", label: "外观", note: "主题与动效", icon: "palette" },
        { id: "ai", label: "AI 接入", note: "模型与临时凭据", icon: "spark" },
        { id: "system", label: "系统设置", note: "本地运行边界", icon: "shield" }
    ];
    nav.replaceChildren();
    sections.forEach((section) => {
        const button = element(
            "button",
            `settings-nav__item${state.settingsSection === section.id ? " is-active" : ""}`
        );
        button.type = "button";
        button.setAttribute("aria-controls", "settings-panel");
        if (state.settingsSection === section.id) {
            button.setAttribute("aria-current", "page");
        }
        button.innerHTML = `<i>${icon(section.icon, 18)}</i>`;
        const copy = element("span");
        copy.append(
            element("strong", "", section.label),
            element("small", "", section.note)
        );
        button.append(copy);
        button.addEventListener("click", () => {
            state.settingsSection = section.id;
            renderSettings({ focusPanel: true });
        });
        nav.append(button);
    });

    // 只替换内容面板，保留 dialog、导航容器和状态 live region，避免设置切换丢失
    // 无障碍关系或把焦点抛回 body。
    panel.replaceChildren();
    if (state.settingsSection === "appearance") {
        renderAppearanceSettings(panel);
    } else if (state.settingsSection === "ai") {
        renderAiSettings(panel);
    } else {
        renderSystemSettings(panel);
    }
    const heading = panel.querySelector(".settings-panel__header h3");
    if (heading) {
        heading.id = "settings-panel-title";
        heading.tabIndex = -1;
        panel.setAttribute("aria-labelledby", heading.id);
    }
    if (focusPanel) {
        const target = heading ?? panel.querySelector("button, input, select, textarea, [tabindex]");
        target?.focus({ preventScroll: true });
    }
}

/**
 * 渲染外观设置。
 *
 * @param {HTMLElement} panel 面板
 */
function renderAppearanceSettings(panel) {
    const settings = settingsStore.getSnapshot();
    const header = element("header", "settings-panel__header");
    header.append(
        element("p", "eyebrow", "APPEARANCE"),
        element("h3", "", "外观"),
        element("p", "", "主题以目录包提供 CSS 与动效；增删主题后重启宿主生效，不支持主题 JavaScript。")
    );
    panel.append(header);

    if (state.themeIssues.length > 0) {
        const issues = element("section", "theme-issues");
        issues.setAttribute("aria-labelledby", "theme-issues-title");
        issues.append(element("h4", "", "部分主题未加载"));
        issues.firstElementChild.id = "theme-issues-title";
        const list = element("ul", "");
        state.themeIssues.forEach((issue) => {
            list.append(element(
                "li",
                "",
                `${issue.id || "themes"} · ${issue.code || "THEME_ISSUE"} · ${issue.message || "主题不可用"}`
            ));
        });
        issues.append(list);
        panel.append(issues);
    }
    if (state.builtInFallbackActive) {
        const notice = element("section", "theme-catalog-note");
        notice.setAttribute("aria-labelledby", "theme-catalog-note-title");
        notice.append(
            element("h4", "", "未配置可用默认主题"),
            element("p", "", "自动回退不会选择排序第一项；自定义主题仍可在列表中明确选择。")
        );
        notice.firstElementChild.id = "theme-catalog-note-title";
        panel.append(notice);
    }

    const fields = element("div", "settings-fields");
    const themeField = element("fieldset", "setting-field");
    themeField.append(
        element("legend", "", "界面主题"),
        element("p", "", "列表由 /api/themes 提供；目录变更需重启，前端不保存第二套主题清单。")
    );
    const themeGrid = element("div", "theme-grid");
    const themes = [...state.themes].sort((left, right) =>
        Number(left.order ?? 0) - Number(right.order ?? 0)
            || String(left.id).localeCompare(String(right.id)));
    if (themes.length === 0) {
        themeGrid.append(element("p", "empty-state", "未发现可用主题，界面使用内置安全外观。"));
    }
    // 主题单选只写入本地外观设置；真正的 CSS 获取、代次竞争和失败回退由 ThemeManager
    // 统一处理，避免界面层同时维护第二套可应用主题判定。
    themes.forEach((option) => {
        const label = element("label", "theme-option");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "theme";
        input.value = option.id;
        input.checked = settings.theme === option.id;
        const card = element("span", "theme-option__card");
        card.append(
            element("strong", "", option.label || option.id),
            element("small", "muted", option.note || (option.default ? "默认主题" : "本地主题"))
        );
        label.append(input, card);
        input.addEventListener("change", () => {
            if (input.checked) {
                clearThemeSwitchAlert();
                settingsStore.update({ theme: option.id });
            }
        });
        themeGrid.append(label);
    });
    themeField.append(themeGrid);
    fields.append(themeField);

    const aiIconField = element("fieldset", "setting-field");
    aiIconField.append(
        element("legend", "", "AI 图标"),
        element("p", "", "用于收起态入口与展开后的生成按钮；选择仅保存在当前浏览器。")
    );
    const aiIconGrid = element("div", "ai-icon-grid");
    AI_ICON_OPTIONS.forEach((option) => {
        const label = element("label", "ai-icon-option");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "aiIcon";
        input.value = option.value;
        input.checked = settings.aiIcon === option.value;
        const card = element("span", "ai-icon-option__card");
        const iconPreview = element("span", "ai-icon-option__icon");
        iconPreview.innerHTML = icon(option.value, 20);
        card.append(iconPreview, element("strong", "", option.label));
        label.append(input, card);
        input.addEventListener("change", () => {
            if (input.checked) {
                settingsStore.update({ aiIcon: option.value });
            }
        });
        aiIconGrid.append(label);
    });
    aiIconField.append(aiIconGrid);

    const motionField = element("fieldset", "setting-field");
    motionField.append(
        element("legend", "", "交互动效"),
        element("p", "", "关闭后不会隐藏任何状态，仅移除动画和过渡。")
    );
    const segmented = element("div", "segmented");
    MOTION_OPTIONS.forEach((option) => {
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "motion";
        input.value = option.value;
        input.checked = settings.motion === option.value;
        const span = element("span", "", option.label);
        label.append(input, span);
        input.addEventListener("change", () => {
            if (input.checked) {
                settingsStore.update({ motion: option.value });
            }
        });
        segmented.append(label);
    });
    motionField.append(segmented);

    const notifications = element("div", "setting-field setting-field--row");
    const notificationCopy = element("div");
    notificationCopy.append(
        element("label", "", "界面通知"),
        element("p", "", "仅控制本页提示条，不申请浏览器系统通知权限。")
    );
    notifications.append(notificationCopy, switchControl(
        "notifications",
        settings.notifications
    ));
    fields.append(aiIconField, motionField, notifications);
    panel.append(fields);
}

/**
 * 渲染独立的 AI 接入设置分区。
 *
 * @param {HTMLElement} panel 面板
 */
function renderAiSettings(panel) {
    const settings = state.aiSettings;
    const header = element("header", "settings-panel__header");
    header.append(
        element("p", "eyebrow", "AI PROVIDER"),
        element("h3", "", "AI 接入"),
        element(
            "p",
            "",
            "API Key 只保存在当前 Python AI 服务进程内，刷新或退出后失效；不会写入仓库、日志或浏览器存储。"
        )
    );
    panel.append(header);

    if (state.aiSettingsLoading) {
        panel.append(element("p", "settings-note", "正在读取 AI 接入状态…"));
    }
    if (state.aiSettingsError) {
        const issue = element("section", "theme-issues");
        issue.append(
            element("h4", "", "AI 设置不可用"),
            element("p", "", state.aiSettingsError)
        );
        panel.append(issue);
    }

    const fields = element("div", "settings-fields");
    const modeField = element("fieldset", "setting-field");
    modeField.append(
        element("legend", "", "接入模式"),
        element("p", "", "DeepSeek 使用固定官方地址和模型；自定义模式需要完整连接信息。")
    );
    const modeSelect = document.createElement("select");
    modeSelect.id = "ai-settings-mode";
    modeSelect.className = "settings-input";
    AI_MODES.forEach((option) => {
        const item = document.createElement("option");
        item.value = option.value;
        item.textContent = option.label;
        item.selected = settings.mode === option.value;
        modeSelect.append(item);
    });
    modeField.append(modeSelect);

    const keyField = element("fieldset", "setting-field");
    keyField.append(
        element("legend", "", "API Key"),
        element(
            "p",
            "",
            settings.configured
                ? "当前进程已有临时凭据。输入新 Key 可替换，留空则保留当前值。"
                : "输入后将立即用于当前进程；不会写入磁盘。"
        )
    );
    const keyInput = document.createElement("input");
    keyInput.id = "ai-settings-api-key";
    keyInput.className = "settings-input";
    keyInput.type = "password";
    keyInput.autocomplete = "off";
    keyInput.spellcheck = false;
    keyInput.placeholder = settings.configured ? "已配置，输入新 Key 可替换" : "输入 API Key";
    keyField.append(keyInput);

    const customFields = element("fieldset", "setting-field ai-settings-custom");
    customFields.append(
        element("legend", "", "自定义连接"),
        element("p", "", "仅自定义模式使用；默认只允许 HTTPS。")
    );
    const baseUrlInput = document.createElement("input");
    baseUrlInput.id = "ai-settings-base-url";
    baseUrlInput.className = "settings-input";
    baseUrlInput.type = "url";
    baseUrlInput.autocomplete = "off";
    baseUrlInput.placeholder = "https://api.example.com";
    baseUrlInput.value = settings.baseUrl || "";
    const modelInput = document.createElement("input");
    modelInput.id = "ai-settings-model";
    modelInput.className = "settings-input";
    modelInput.type = "text";
    modelInput.autocomplete = "off";
    modelInput.placeholder = "模型标识";
    modelInput.value = settings.model || "";
    const customGrid = element("div", "settings-input-grid");
    const baseUrlLabel = element("label", "settings-input-label");
    baseUrlLabel.append(element("span", "", "API Base URL"), baseUrlInput);
    const modelLabel = element("label", "settings-input-label");
    modelLabel.append(element("span", "", "Model"), modelInput);
    customGrid.append(baseUrlLabel, modelLabel);
    customFields.append(customGrid);

    const deleteRow = element("div", "setting-field setting-field--row ai-danger-setting");
    const deleteCopy = element("div");
    deleteCopy.append(
        element("label", "", "允许明确意图直执删除"),
        element(
            "p",
            "",
            "默认关闭。开启后删除仍只进入项目回收站，并保留快照、审计和回滚。"
        )
    );
    const deleteInput = document.createElement("input");
    deleteInput.type = "checkbox";
    deleteInput.checked = settings.allowDeleteDirect === true;
    deleteInput.dataset.settingKey = "allowDeleteDirect";
    deleteInput.setAttribute("aria-label", "允许明确意图直执删除");
    deleteRow.append(deleteCopy, deleteInput);

    const planRow = settingRow(
        "aiPlanEnabled",
        "允许 AI 生成计划",
        state.aiPlanFeatureLoading
            ? "正在读取宿主执行策略。"
            : "默认开启。关闭后 AI 只返回直接动作或澄清问题，不再生成需要确认的计划。",
        state.aiPlanEnabled,
        (enabled, input) => void persistAiPlanFeature(enabled, input),
        state.aiPlanFeatureLoading
            || state.aiPlanFeatureSaving
            || state.aiSettingsSaving
            || state.aiSettingsTesting,
        "允许 AI 生成计划"
    );

    const queryResultsRow = settingRow(
        "allowSendQueryResultsToModel",
        "发送本地查询结果给模型",
        "默认不把本地查询结果发送给模型，开启后仅发送有上限的查询结果。",
        settings.allowSendQueryResultsToModel === true,
        () => {},
        state.aiSettingsLoading || state.aiSettingsSaving || state.aiSettingsTesting,
        "发送本地查询结果给模型"
    );

    const policyField = element("fieldset", "setting-field");
    policyField.append(
        element("legend", "", "执行策略"),
        element(
            "p",
            "",
            "风险开关由宿主保存；本地查询结果是否发送给模型由下方开关控制。"
        )
    );
    const policyRows = element("div", "settings-fields");
    policyRows.append(deleteRow, planRow, queryResultsRow);
    policyField.append(policyRows);

    const actions = element("div", "settings-actions");
    const saveButton = element("button", "primary-button", "保存临时设置");
    saveButton.type = "button";
    const testButton = element("button", "text-button", "保存并测试连接");
    testButton.type = "button";
    const clearButton = element("button", "text-button", "清除临时凭据");
    clearButton.type = "button";
    actions.append(saveButton, testButton, clearButton);

    // DeepSeek 模式固定使用宿主默认地址和模型；只有 CUSTOM 模式展示并启用连接字段。
    const updateModeFields = () => {
        const custom = modeSelect.value === "CUSTOM";
        customFields.hidden = !custom;
        baseUrlInput.disabled = !custom;
        modelInput.disabled = !custom;
    };
    modeSelect.addEventListener("change", updateModeFields);
    // 删除直执是高风险能力开关：用户取消原生确认时恢复关闭，保存成功后仍由宿主执行
    // 回收站、快照、审计和回滚约束。
    deleteInput.addEventListener("change", () => {
        if (deleteInput.checked && !window.confirm(
            "开启后，明确意图的删除可能直接进入回收站。仍会保留快照、审计和回滚。确认开启？"
        )) {
            deleteInput.checked = false;
        }
    });
    saveButton.addEventListener("click", () => void persistAiSettings(panel));
    testButton.addEventListener("click", () => void persistAiSettings(panel, { test: true }));
    clearButton.addEventListener("click", () => void clearAiSettings(panel));

    fields.append(modeField, keyField, customFields, policyField, actions);
    panel.append(fields);
    updateModeFields();
}

/**
 * 保存当前进程内 AI 设置；测试按钮会先保存再测试。
 *
 * 保存期间统一禁用一个面板内的按钮以避免并发覆盖，但不持久化 API Key。成功响应会
 * 重新归一化并同步给 AI Dock；失败只展示宿主错误，不回退到本地规则或伪造已连接。
 *
 * 副作用：调用宿主 AI 设置接口、更新 state.aiSettings、通知 Dock、重绘设置面板。
 *
 * @param {HTMLElement} panel 设置面板
 * @param {{test?: boolean}} options 选项
 * @returns {Promise<void>}
 */
async function persistAiSettings(panel, { test = false } = {}) {
    if (state.aiSettingsSaving || state.aiSettingsTesting) {
        return;
    }
    const modeSelect = panel.querySelector("#ai-settings-mode");
    const keyInput = panel.querySelector("#ai-settings-api-key");
    const baseUrlInput = panel.querySelector("#ai-settings-base-url");
    const modelInput = panel.querySelector("#ai-settings-model");
    const deleteInput = panel.querySelector('[data-setting-key="allowDeleteDirect"]');
    const queryResultsInput = panel.querySelector(
        '[data-setting-key="allowSendQueryResultsToModel"]'
    );
    if (!modeSelect
        || !keyInput
        || !baseUrlInput
        || !modelInput
        || !deleteInput
        || !queryResultsInput) {
        return;
    }
    const draft = {
        mode: modeSelect.value,
        apiKey: keyInput.value,
        baseUrl: baseUrlInput.value,
        model: modelInput.value
    };
    const validation = validateAiSettingsDraft(draft, {
        configured: state.aiSettings.configured,
        allowLocalProvider: state.aiSettings.allowLocalProvider
    });
    if (validation) {
        setSettingsStatus(validation, { error: true });
        keyInput.focus({ preventScroll: true });
        return;
    }

    state.aiSettingsSaving = true;
    const buttons = [...panel.querySelectorAll("button")];
    buttons.forEach((button) => {
        button.disabled = true;
    });
    setSettingsStatus(test ? "正在保存并测试模型连接…" : "正在保存临时 AI 设置…");
    try {
        const saved = await api.saveAiSettings({
            mode: draft.mode,
            apiKey: draft.apiKey.trim(),
            baseUrl: draft.mode === "CUSTOM" ? draft.baseUrl.trim() : null,
            model: draft.mode === "CUSTOM" ? draft.model.trim() : null,
            allowDeleteDirect: deleteInput.checked,
            allowSendQueryResultsToModel: queryResultsInput.checked
        });
        state.aiSettings = normalizeAiSettings(saved);
        state.aiSettingsError = "";
        aiDialog?.setAiSettings?.(state.aiSettings);
        if (test) {
            state.aiSettingsTesting = true;
            const result = await api.testAiSettings();
            setSettingsStatus(result?.message || "模型连接成功");
            toast(result?.message || "模型连接成功");
        } else {
            setSettingsStatus("AI 临时设置已保存；API Key 不会写入磁盘。");
            toast("AI 临时设置已保存。");
        }
        renderSettings({ focusPanel: true });
    } catch (error) {
        const message = `AI 设置失败：${errorMessage(error)}`;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    } finally {
        state.aiSettingsSaving = false;
        state.aiSettingsTesting = false;
        buttons.forEach((button) => {
            button.disabled = false;
        });
    }
}

/**
 * 清除当前进程内 AI 凭据。
 *
 * @param {HTMLElement} panel 设置面板
 */
async function clearAiSettings(panel) {
    if (state.aiSettingsSaving || state.aiSettingsTesting) {
        return;
    }
    state.aiSettingsSaving = true;
    const buttons = [...panel.querySelectorAll("button")];
    buttons.forEach((button) => {
        button.disabled = true;
    });
    try {
        const cleared = await api.clearAiSettings();
        state.aiSettings = normalizeAiSettings(cleared);
        state.aiSettingsError = "";
        aiDialog?.setAiSettings?.(state.aiSettings);
        setSettingsStatus("AI 临时凭据已清除。");
        toast("AI 临时凭据已清除。");
        renderSettings({ focusPanel: true });
    } catch (error) {
        const message = `清除 AI 凭据失败：${errorMessage(error)}`;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    } finally {
        state.aiSettingsSaving = false;
        buttons.forEach((button) => {
            button.disabled = false;
        });
    }
}

/**
 * 将 AI 计划返回开关写入宿主；失败时恢复开关并保留错误提示。
 *
 * @param {boolean} enabled 目标状态
 * @param {HTMLInputElement} input 开关控件
 */
async function persistAiPlanFeature(enabled, input) {
    if (state.aiPlanFeatureSaving) {
        return;
    }
    const previous = state.aiPlanEnabled;
    state.aiPlanFeatureSaving = true;
    input.disabled = true;
    input.removeAttribute("aria-invalid");
    setSettingsStatus("正在保存 AI 执行策略…");
    try {
        const payload = await api.setAiPlanEnabled(enabled);
        let authoritative = normalizeAiPlanFeature(
            payload,
            typeof enabled === "boolean" ? enabled : true
        );
        if (!payload || typeof payload.aiPlanEnabled !== "boolean") {
            const featurePayload = await api.getSystemFeatures();
            authoritative = normalizeAiPlanFeature(featurePayload, authoritative);
        }
        state.aiPlanFeatureLoading = false;
        state.aiPlanEnabled = authoritative;
        input.checked = authoritative;
        setSettingsStatus(
            authoritative
                ? "AI 可以生成计划，由宿主保存。"
                : "AI 已禁止生成计划，只返回动作或澄清问题。"
        );
        toast(authoritative ? "AI 计划返回已开启。" : "AI 计划返回已关闭。");
    } catch (error) {
        input.checked = previous;
        input.setAttribute("aria-invalid", "true");
        const message = `AI 执行策略设置失败：${errorMessage(error)}`;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    } finally {
        state.aiPlanFeatureSaving = false;
        input.disabled = false;
        input.focus({ preventScroll: true });
    }
}

/**
 * 渲染系统信息设置。
 *
 * @param {HTMLElement} panel 面板
 */
function renderSystemSettings(panel) {
    const header = element("header", "settings-panel__header");
    header.append(
        element("p", "eyebrow", "LOCAL SYSTEM"),
        element("h3", "", "系统设置"),
        element("p", "", "宿主只监听本机回环地址，当前版本只操作应用自管沙箱。")
    );
    panel.append(header);
    const fields = element("div", "settings-fields");
    const buildField = element("fieldset", "setting-field");
    buildField.append(
        element("legend", "", "模块构建并发"),
        element(
            "p",
            "",
            state.buildSettingsLoading
                ? "正在读取宿主的后台构建设置。"
                : "并行构建模块，完成后由单一发布线程依次校验和加载；提高并发会增加内存占用。"
        )
    );
    const buildSegmented = element("div", "segmented segmented--four");
    const buildDisabled = state.buildSettingsLoading || state.buildSettingsSaving;
    for (let value = state.buildSettings.minimum; value <= state.buildSettings.maximum; value += 1) {
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "buildMaxParallel";
        input.value = String(value);
        input.checked = state.buildSettings.maxParallel === value;
        input.disabled = buildDisabled;
        input.dataset.settingKey = "buildMaxParallel";
        input.setAttribute("aria-label", `模块构建并发 ${value}`);
        label.append(input, element("span", "", String(value)));
        input.addEventListener("change", () => {
            if (input.checked) {
                void persistBuildConcurrency(value, input);
            }
        });
        buildSegmented.append(label);
    }
    buildField.append(buildSegmented);
    if (state.buildSettingsError) {
        buildField.append(element("p", "settings-note", state.buildSettingsError));
    }
    fields.append(buildField);
    const planningDisabled = state.planningFeatureLoading
        || state.planningFeatureSaving
        || state.planBusy
        || state.executing;
    fields.append(settingRow(
        "planningEnabled",
        "计划功能",
        state.planningFeatureLoading
            ? "正在读取宿主功能状态。"
            : "由本地宿主持久化；关闭后隐藏计划入口，模块与历史记录保持可用。",
        state.planningEnabled,
        (enabled, input) => void persistPlanningFeature(enabled, input),
        planningDisabled,
        "计划功能"
    ));
    [
        ["计划器", "本地规则计划器 · 未连接外部模型"],
        ["文件边界", safeWorkspacePath()],
        ["删除策略", "仅移入 .orderly-data/trash，不提供物理删除"],
        ["执行担保", state.planningEnabled
            ? "计划确认 → 快照 → 执行 → 审计 → 失败回滚"
            : "计划功能已关闭；模块浏览与历史记录仍可用"]
    ].forEach(([label, value]) => {
        const row = element("div", "setting-field");
        row.append(
            element("label", "", label),
            element("p", "", value)
        );
        fields.append(row);
    });
    panel.append(fields);
}

/**
 * 保存后台模块构建并发数；失败时恢复到宿主已确认的值。
 *
 * @param {number} value 目标并发数
 * @param {HTMLInputElement} input 当前单选项
 */
async function persistBuildConcurrency(value, input) {
    if (state.buildSettingsSaving) {
        return;
    }
    const previous = state.buildSettings.maxParallel;
    state.buildSettingsSaving = true;
    input.disabled = true;
    input.removeAttribute("aria-invalid");
    setSettingsStatus("正在保存模块构建并发设置…");
    let saved = false;
    try {
        const payload = await api.setBuildConcurrency(value);
        state.buildSettings = normalizeBuildSettings(payload, value);
        state.buildSettingsError = "";
        state.buildSettingsLoading = false;
        input.checked = state.buildSettings.maxParallel === value;
        setSettingsStatus(`模块构建并发已设置为 ${state.buildSettings.maxParallel}。`);
        toast(`模块构建并发已设置为 ${state.buildSettings.maxParallel}。`);
        saved = true;
    } catch (error) {
        input.checked = state.buildSettings.maxParallel === previous;
        input.setAttribute("aria-invalid", "true");
        const message = `模块构建并发设置失败：${errorMessage(error)}`;
        state.buildSettingsError = message;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    } finally {
        state.buildSettingsSaving = false;
        if (saved) {
            renderSettings({ focusPanel: true });
        } else {
            input.disabled = false;
            input.focus({ preventScroll: true });
        }
    }
}

/**
 * 将计划功能开关写入宿主；失败时恢复开关并保留错误提示。
 *
 * @param {boolean} enabled 目标状态
 * @param {HTMLInputElement} input 开关控件
 */
async function persistPlanningFeature(enabled, input) {
    if (state.planningFeatureSaving) {
        return;
    }
    const previous = state.planningEnabled;
    state.planningFeatureSaving = true;
    input.disabled = true;
    input.removeAttribute("aria-invalid");
    setSettingsStatus("正在保存计划功能设置…");
    try {
        const payload = await api.setPlanningEnabled(enabled);
        let authoritative = normalizePlanningFeature(
            payload,
            typeof enabled === "boolean" ? enabled : false
        );
        if (!payload || typeof payload.planningEnabled !== "boolean") {
            const featurePayload = await api.getSystemFeatures();
            authoritative = normalizePlanningFeature(featurePayload, authoritative);
        }
        state.planningFeatureLoading = false;
        applyPlanningFeatureState(authoritative);
        input.checked = authoritative;
        setSettingsStatus(
            authoritative
                ? "计划功能已开启，由宿主保存。"
                : "计划功能已关闭，由宿主保存。"
        );
        toast(authoritative ? "计划功能已开启。" : "计划功能已关闭。");
    } catch (error) {
        input.checked = previous;
        input.setAttribute("aria-invalid", "true");
        const message = `计划功能设置失败：${errorMessage(error)}`;
        setSettingsStatus(message, { error: true });
        toast(message, { error: true });
    } finally {
        state.planningFeatureSaving = false;
        input.disabled = false;
        input.focus({ preventScroll: true });
    }
}

/**
 * 创建开关行。
 *
 * @param {string} key 设置键
 * @param {string} title 标题
 * @param {string} description 描述
 * @param {boolean} checked 当前值
 * @param {(checked: boolean, input: HTMLInputElement) => void | null} onChange 变更回调
 * @param {boolean} disabled 是否禁用
 * @param {string} accessibleLabel 无障碍标签
 * @returns {HTMLElement} 行
 */
function settingRow(
    key,
    title,
    description,
    checked,
    onChange = null,
    disabled = false,
    accessibleLabel = title
) {
    const row = element("div", "setting-field setting-field--row");
    const copy = element("div");
    copy.append(
        element("label", "", title),
        element("p", "", description)
    );
    const control = switchControl(key, checked, onChange, accessibleLabel);
    control.querySelector("input").disabled = disabled;
    row.append(copy, control);
    return row;
}

/**
 * 创建开关控件。
 *
 * @param {string} key 设置键
 * @param {boolean} checked 当前值
 * @param {(checked: boolean, input: HTMLInputElement) => void | null} onChange 变更回调
 * @param {string} accessibleLabel 无障碍标签
 * @returns {HTMLElement} 控件
 */
function switchControl(key, checked, onChange = null, accessibleLabel = key) {
    const label = element("label", "switch");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = checked;
    input.dataset.settingKey = key;
    input.setAttribute("aria-label", accessibleLabel);
    input.addEventListener("change", () => {
        if (onChange) {
            onChange(input.checked, input);
        } else {
            settingsStore.update({ [key]: input.checked });
        }
    });
    label.append(input, element("span"));
    return label;
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap, { once: true });
} else {
    void bootstrap();
}
