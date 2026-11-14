import { ApiError, OrderlyApi } from "./api.js";
import { hydrateIcons, icon } from "./icons.js";
import {
    RISK_LABEL,
    flattenText,
    formatDate,
    mergeEvents,
    normalizeEvent,
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
    normalizeModuleCatalogs,
    normalizeModulePreferences,
    normalizeModuleUi,
    readCount,
    reconcileModulePreferences,
    reorderBefore,
    reorderRelative,
    resolveModuleUiCatalog,
    sortModules
} from "./modules.js";
import {
    MOTION_OPTIONS,
    normalizeThemeCatalog,
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
            { id: "history", label: "执行记录", caption: "审计与回滚", icon: "history" }
        ]
    }
]);

const state = {
    activeView: "overview",
    health: null,
    modules: [],
    modulePreferences: normalizeModulePreferences({}),
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
    settingsSection: "appearance",
    moduleUi: {},
    moduleErrors: {},
    moduleCounts: {},
    dragModuleId: null,
    moduleAssetVersion: String(Date.now())
};

const systemAdminApi = createSystemAdminApi();
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
 * 启动页面。
 */
async function bootstrap() {
    hydrateIcons();
    bindGlobalControls();
    bindSettingsDialog();

    const [moduleResult, systemModuleResult, preferenceResult, themeResult] = await Promise.allSettled([
        api.getModules(),
        api.getSystemModules(),
        api.getModulePreferences(),
        api.getThemes()
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
    const themeCatalog = normalizeThemeCatalog(
        themeResult.status === "fulfilled" ? themeResult.value : {}
    );
    state.themes = themeCatalog.items;
    state.themeIssues = themeCatalog.issues;
    state.builtInFallbackActive = themeCatalog.builtInFallbackActive;
    themeManager.setCatalog(themeCatalog);

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
    renderCommandExamples();

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

    void navigate("overview");
}

/** 并行读取模块自带 UI 清单；单个模块失败不阻断框架启动。 */
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
    return state.activeView !== "overview"
        && state.activeView !== "history"
        && !moduleForView(state.activeView);
}

function refreshVisibleFrameworkViews() {
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    renderCommandExamples();
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        void loadOverviewCounts();
        renderModuleCards();
    }
    if (document.querySelector("#settings-dialog")?.open) {
        renderSettings();
    }
}

async function reloadModuleCatalogAfterRefresh(refreshPayload) {
    const [moduleResult, systemModuleResult, preferenceResult] = await Promise.allSettled([
        api.getModules(),
        api.getSystemModules(),
        api.getModulePreferences()
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
        getState() {
            return systemAdminSnapshot();
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

        getManagementOrder() {
            return [...state.modulePreferences.managementOrder];
        }
    });
}

/**
 * 绑定全局按钮与键盘入口。
 */
function bindGlobalControls() {
    document.querySelector("#open-settings")?.addEventListener("click", openSettings);
    document.querySelector("#mobile-menu")?.addEventListener("click", openMobileNav);
    document.querySelector("#mobile-scrim")?.addEventListener("click", () => closeMobileNav());
    document.querySelector("#dock-toggle")?.addEventListener("click", () => {
        const dock = document.querySelector("#command-dock");
        const expanded = dock?.classList.toggle("is-expanded") ?? false;
        if (dock) {
            dock.dataset.motionState = expanded ? "expanded" : "collapsed";
            dock.dataset.motionPhase = "active";
        }
        document.querySelector("#dock-toggle")?.setAttribute("aria-expanded", String(expanded));
        if (expanded) {
            document.querySelector("#command-input")?.focus();
        }
    });

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

    document.querySelector("#command-form")?.addEventListener("submit", (event) => {
        event.preventDefault();
        const input = document.querySelector("#command-input");
        void draftGoal(input?.value ?? "");
    });

    document.querySelector("#command-input")?.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            document.querySelector("#command-form")?.requestSubmit();
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

    window.addEventListener("pagehide", () => {
        cancelNavigationDrag();
        eventStream?.close();
    }, { once: true });
}

/**
 * 绑定桌面图标侧栏的指针与焦点展开入口。
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
    return Number.isFinite(width) && width > 0 ? width : 64;
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
    syncSettingControls(settings);
    void applyThemeSelection(settings.theme);
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        renderModuleCards();
    }
}

/**
 * 应用目录主题；请求代次由 ThemeManager 保证旧响应不会覆盖新选择。
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
    const sections = [
        { ...NAV_SECTIONS[0], items: [...NAV_SECTIONS[0].items, ...moduleItems] },
        NAV_SECTIONS[1]
    ];
    container.replaceChildren();

    sections.forEach((section) => {
        const visibleItems = section.items.filter((item) => {
            const haystack = `${item.label} ${item.caption}`.toLocaleLowerCase("zh-CN");
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
            button.innerHTML = `<span class="nav-icon">${icon(item.icon)}</span>`;
            if (item.moduleId) {
                const handle = element("span", "nav-drag-handle", "⋮");
                handle.setAttribute("aria-hidden", "true");
                button.prepend(handle);
            }
            const copy = element("span", "nav-copy");
            copy.append(element("strong", "", item.label));
            button.append(copy);
            button.addEventListener("click", (event) => {
                if (performance.now() < suppressNavigationClickUntil) {
                    event.preventDefault();
                    return;
                }
                void navigate(item.id);
            });
            if (item.moduleId) {
                button.addEventListener("pointerdown", (event) => {
                    beginNavigationDrag(event, button, item.moduleId);
                });
                button.addEventListener("keydown", (event) => {
                    if (!event.altKey || !["ArrowUp", "ArrowDown"].includes(event.key)) return;
                    event.preventDefault();
                    changeSidebarModuleOrder(item.moduleId, event.key === "ArrowUp" ? -1 : 1);
                });
            }
            list.append(button);
        });
        group.append(list);
        if (section.id === "workspace" && state.modules.length === 0) {
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
    closeMobileNav();
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    const selectedModule = moduleForView(view);
    const labels = {
        overview: "总览",
        history: "执行记录"
    };
    const viewTitle = selectedModule?.title ?? labels[view] ?? "模块";
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
 * 从模块 JAR 加载自有视图。样式和脚本只属于当前模块，失败时局部降级。
 *
 * @param {object} module 模块契约
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
            element,
            icon,
            toast,
            fillCommand,
            formatDate
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
    main.innerHTML = `
        <div class="view-stack" data-motion-scope="overview" data-motion-state="entering" data-motion-phase="active">
            <section class="hero-panel">
                <div class="hero-content">
                    <p class="eyebrow">CONTROLLED AGENT WORKFLOW</p>
                    <h1>让 AI 把事做完，<br><em>也让你随时踩刹车。</em></h1>
                    <p>
                        已安装模块通过统一契约接入，并在一条可审查的流程中协作。
                        本版本只操作应用自管沙箱，先生成计划，确认之后才会写入。
                    </p>
                    <div class="hero-tags">
                        <span>${icon("shield")} 本地优先</span>
                        <span>${icon("route")} 计划先于执行</span>
                        <span>${icon("history")} 回收站与回滚</span>
                    </div>
                </div>
                <div class="hero-diagram" aria-label="已安装模块汇聚到归序">
                    <span class="orbit-ring"></span>
                    <span class="orbit-core"><strong>归序</strong><small>工作流中枢</small></span>
                    <span class="orbit-module-nodes" id="orbit-module-nodes"></span>
                </div>
            </section>

            <section class="surface-grid" id="module-card-grid" aria-label="已安装模块摘要"></section>
            <p class="sr-only" id="module-order-status" aria-live="polite"></p>

            <div class="workflow-grid">
                <section class="panel">
                    <header class="panel__header">
                        <div>
                            <p class="eyebrow">PLAN PREVIEW</p>
                            <h2>计划预览</h2>
                            <p>确认之前不会产生任何真实变更。</p>
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
                        <li>当前位置是本地规则计划器，不连接外部模型。</li>
                    </ul>
                </aside>
            </div>

            <div class="workflow-grid">
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
        </div>
    `;

    hydrateIcons(main);
    renderModuleCards();
    renderPlan();
    renderTimeline();
    renderRunSummary();
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
        grid.append(element(
            "div",
            "panel empty-state module-empty-state",
            state.modules.length
                ? "当前没有已启用的功能模块；可在模组设置中重新启用。"
                : "尚未安装功能模块；添加 Module/module_* 并重新构建后会显示在这里。"
        ));
        return;
    }

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
 * 渲染计划预览。
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
            element("h3", "", "从右下角告诉归序你想完成什么"),
            element(
                "p",
                "",
                examples.length
                    ? "输入已安装模块提供的示例指令。宿主会先返回步骤与风险，不会立即执行。"
                    : "当前没有已安装模块提供可用指令；添加 Module/module_* 并重新构建后再试。"
            )
        );
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
    const cleanGoal = String(goal ?? "").trim();
    if (!cleanGoal) {
        toast("请先输入一条工作流目标。");
        document.querySelector("#command-input")?.focus();
        return;
    }
    if (state.planBusy) {
        return;
    }

    state.planBusy = true;
    document.querySelector(".app-shell")?.setAttribute("data-motion-state", "busy");
    const send = document.querySelector("#send-command");
    const note = document.querySelector("#command-note");
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
 * 执行已确认计划。
 */
async function executePlan() {
    if (!state.plan || state.executing) {
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
    const shell = document.querySelector(".app-shell");
    shell?.setAttribute("data-motion-state", "busy");
    state.currentRun = {
        planId: state.plan.planId,
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
            status: "FAILED",
            message: errorMessage(error),
            canRollback: false
        };
        toast(errorMessage(error), { error: true });
    } finally {
        state.executing = false;
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
 * 将事件状态同步回计划步骤。
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
        copy.append(
            element("strong", "", statusLabel(event.status)),
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

    if (["COMPLETED", "PARTIAL"].includes(status) && (run.canRollback || run.planId)) {
        const rollback = element("button", "danger-button", "撤销本次任务");
        rollback.type = "button";
        rollback.addEventListener("click", () => void rollbackCurrent());
        summary.append(rollback);
    }
}

/**
 * 回滚当前任务。
 */
async function rollbackCurrent() {
    const planId = state.currentRun?.planId || state.plan?.planId;
    if (!planId) {
        toast("没有可撤销的任务。");
        return;
    }
    try {
        const payload = await api.rollbackPlan(planId);
        applyRunPayload({
            ...(payload && typeof payload === "object" ? payload : {}),
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
 * 加载运行记录和审计事件。
 */
async function loadHistory() {
    const [runPayload, eventPayload] = await Promise.all([
        api.getRuns(),
        api.getEvents()
    ]);
    state.events = mergeEvents(state.events, unwrapList(eventPayload, ["events"]));
    const runs = unwrapList(runPayload, ["runs"]).filter((item) => item && typeof item === "object");
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.innerHTML = `
        <div class="view-stack">
            <header class="page-heading">
                <span class="page-heading__icon">${icon("history", 25)}</span>
                <div>
                    <p class="eyebrow">AUDIT & ROLLBACK</p>
                    <h1>执行记录</h1>
                    <p>运行摘要来自本地宿主；回滚不会删除原审计事件。</p>
                </div>
                <span class="status-chip">只追加事件</span>
            </header>
            <section class="data-card">
                <header class="panel__header">
                    <div>
                        <h2>任务运行</h2>
                        <p>${runs.length} 次运行 · 实际读取自 /api/runs</p>
                    </div>
                </header>
                <div id="run-list"></div>
            </section>
            <section class="panel">
                <header class="panel__header">
                    <div>
                        <p class="eyebrow">EVENT LOG</p>
                        <h2>最近事件</h2>
                    </div>
                    <span class="status-chip">${state.events.length} 条</span>
                </header>
                <ol class="timeline" id="history-timeline"></ol>
            </section>
        </div>
    `;
    hydrateIcons(main);
    renderHistoryRuns(runs);
    renderHistoryEvents();
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
    if (!runs.length) {
        container.append(emptyState("尚无运行记录", "确认并执行一条计划后会在这里留下审计摘要。"));
        return;
    }
    const list = element("div", "run-list");
    runs.forEach((run) => {
        const planId = String(run.planId ?? run.id ?? "");
        const status = String(run.status ?? "COMPLETED").toUpperCase();
        const row = element("article", "run-row");
        const copy = element("div");
        copy.append(
            element("h3", "", String(run.goal ?? run.message ?? "未命名任务")),
            element(
                "p",
                "",
                `${statusLabel(status)} · ${formatDate(run.createdAt ?? run.finishedAt ?? run.timestamp)} · ${planId.slice(0, 16)}`
            )
        );
        const actions = element("div", "run-actions");
        actions.append(element("span", `risk-chip`, statusLabel(status)));
        if (planId && ["COMPLETED", "PARTIAL"].includes(status)) {
            const button = element("button", "text-button", "撤销");
            button.type = "button";
            button.addEventListener("click", async () => {
                try {
                    await api.rollbackPlan(planId);
                    toast("运行已回滚。");
                    await loadHistory();
                } catch (error) {
                    toast(errorMessage(error), { error: true });
                }
            });
            actions.append(button);
        }
        row.append(copy, actions);
        list.append(row);
    });
    container.append(list);
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
        copy.append(
            element("strong", "", statusLabel(event.status)),
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
    const input = document.querySelector("#command-input");
    const dock = document.querySelector("#command-dock");
    if (input) {
        input.value = command;
        input.focus();
        input.setSelectionRange(command.length, command.length);
    }
    dock?.classList.add("is-expanded");
    if (dock) {
        dock.dataset.motionState = "expanded";
        dock.dataset.motionPhase = "active";
    }
    document.querySelector("#dock-toggle")?.setAttribute("aria-expanded", "true");
}

/**
 * 渲染快捷示例。
 */
function renderCommandExamples() {
    const container = document.querySelector("#command-examples");
    const examples = state.modules
        .filter(isModuleVisible)
        .flatMap((module) => state.moduleUi[module.moduleId]?.examples ?? [])
        .slice(0, 4);
    const input = document.querySelector("#command-input");
    if (input) {
        input.placeholder = examples.length
            ? `例如：${examples[0].text}`
            : "请输入已安装模块提供的示例指令";
    }
    if (!container) {
        return;
    }
    container.replaceChildren();
    if (!examples.length) {
        container.hidden = true;
        return;
    }
    container.hidden = false;
    examples.forEach((example) => {
        const button = element("button", "example-chip", example.label);
        button.type = "button";
        button.title = example.text;
        button.addEventListener("click", () => fillCommand(example.text));
        container.append(button);
    });
}

/**
 * 连接执行事件流。
 */
function connectEventStream() {
    eventStream = api.streamEvents({
        onEvent: (raw) => {
            const event = normalizeEvent(raw, state.events.length);
            state.events = mergeEvents(state.events, [event]);
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
 * 绑定设置弹窗。
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
    if (!["appearance", "system"].includes(state.settingsSection)) {
        state.settingsSection = "appearance";
    }
    const sections = [
        { id: "appearance", label: "外观", note: "主题与动效", icon: "palette" },
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

    panel.replaceChildren();
    if (state.settingsSection === "appearance") {
        renderAppearanceSettings(panel);
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
    fields.append(motionField, notifications);
    panel.append(fields);
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
    [
        ["计划器", "本地规则计划器 · 未连接外部模型"],
        ["文件边界", safeWorkspacePath()],
        ["删除策略", "仅移入 .orderly-data/trash，不提供物理删除"],
        ["执行担保", "计划确认 → 快照 → 执行 → 审计 → 失败回滚"]
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
 * 创建开关行。
 *
 * @param {string} key 设置键
 * @param {string} title 标题
 * @param {string} description 描述
 * @param {boolean} checked 当前值
 * @returns {HTMLElement} 行
 */
function settingRow(key, title, description, checked) {
    const row = element("div", "setting-field setting-field--row");
    const copy = element("div");
    copy.append(
        element("label", "", title),
        element("p", "", description)
    );
    row.append(copy, switchControl(key, checked));
    return row;
}

/**
 * 创建开关控件。
 *
 * @param {string} key 设置键
 * @param {boolean} checked 当前值
 * @returns {HTMLElement} 控件
 */
function switchControl(key, checked, onChange = null) {
    const label = element("label", "switch");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = checked;
    input.dataset.settingKey = key;
    input.setAttribute("aria-label", key);
    input.addEventListener("change", () => {
        if (onChange) {
            onChange(input.checked);
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
