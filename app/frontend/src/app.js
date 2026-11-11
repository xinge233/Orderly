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
    moveModuleOrder,
    normalizeModuleUi,
    readCount,
    reorderBefore,
    sortModules
} from "./modules.js";
import {
    isModuleHidden,
    MOTION_OPTIONS,
    reconcileSettingsForCatalog,
    setModuleHidden,
    SettingsStore,
    ThemeManager
} from "./settings.js";

const api = new OrderlyApi();
const settingsStore = new SettingsStore();
const themeManager = new ThemeManager();

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
    themes: [],
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

let eventStream = null;
let eventRenderQueued = false;

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
    node.textContent = message;
    node.classList.toggle("is-error", error);
    node.classList.add("is-visible");
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => {
        node.classList.remove("is-visible");
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

    const [moduleResult, themeResult] = await Promise.allSettled([
        api.getModules(),
        api.getThemes()
    ]);
    state.modules = moduleResult.status === "fulfilled"
        ? unwrapList(moduleResult.value, ["modules"])
            .filter((module) => module && typeof module.moduleId === "string")
            .sort((left, right) => Number(left.order ?? 0) - Number(right.order ?? 0)
                || left.moduleId.localeCompare(right.moduleId))
        : [];
    state.themes = themeResult.status === "fulfilled"
        ? unwrapList(themeResult.value, ["themes"])
            .filter((theme) => theme && typeof theme.id === "string")
        : [];

    await loadModuleUis();
    const reconciled = reconcileSettingsForCatalog(
        settingsStore.getSnapshot(),
        state.modules,
        state.themes
    );
    settingsStore.update(reconciled);
    state.modules = sortModules(state.modules, reconciled.moduleOrderIds);
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
    state.moduleUi = {};
    state.moduleErrors = {};
    const viewOwners = new Map();
    results.forEach((result, index) => {
        const moduleId = state.modules[index]?.moduleId;
        if (!moduleId) {
            return;
        }
        if (result.status === "fulfilled") {
            const ui = result.value[1];
            if (["overview", "history"].includes(ui.view.id)) {
                state.moduleErrors[moduleId] = `模块视图 ID 与框架视图冲突：${ui.view.id}`;
                return;
            }
            const owner = viewOwners.get(ui.view.id);
            if (owner) {
                state.moduleErrors[moduleId] = `模块视图 ID 与 ${owner} 冲突：${ui.view.id}`;
                return;
            }
            viewOwners.set(ui.view.id, moduleId);
            state.moduleUi[moduleId] = ui;
        } else {
            state.moduleErrors[moduleId] = errorMessage(result.reason);
        }
    });
}

/**
 * 绑定全局按钮与键盘入口。
 */
function bindGlobalControls() {
    document.querySelector("#open-settings")?.addEventListener("click", openSettings);
    document.querySelector("#mobile-menu")?.addEventListener("click", openMobileNav);
    document.querySelector("#mobile-scrim")?.addEventListener("click", closeMobileNav);
    document.querySelector("#dock-toggle")?.addEventListener("click", () => {
        const dock = document.querySelector("#command-dock");
        const expanded = dock?.classList.toggle("is-expanded") ?? false;
        document.querySelector("#dock-toggle")?.setAttribute("aria-expanded", String(expanded));
        if (expanded) {
            document.querySelector("#command-input")?.focus();
        }
    });

    const search = document.querySelector("#nav-search");
    search?.addEventListener("input", () => renderNavigation(search.value));
    search?.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            search.value = "";
            renderNavigation("");
            search.blur();
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
            search?.focus();
        }
    });

    window.addEventListener("pagehide", () => {
        eventStream?.close();
    }, { once: true });
}

/**
 * 只在第一次打开时加载主题文件，并立即应用设置。
 *
 * @param {object} settings 设置快照
 */
function applySettings(settings) {
    document.documentElement.dataset.motion = settings.motion;
    if (settings.theme) {
        void themeManager.apply(settings.theme);
    } else {
        themeManager.clear();
    }
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    renderSettings();
    if (state.activeView === "overview" && document.querySelector("#module-card-grid")) {
        renderModuleCards();
    }
}

/**
 * 更新本地服务和计划器状态。
 *
 * @param {unknown} error 连接错误
 */
function updateHealth(error = null) {
    const dot = document.querySelector("#health-dot");
    const label = document.querySelector("#health-label");
    const detail = document.querySelector("#health-detail");
    const planner = document.querySelector("#planner-badge");
    const online = !error && String(state.health?.status ?? "").toUpperCase() === "UP";

    dot?.classList.toggle("is-online", online);
    dot?.classList.toggle("is-offline", Boolean(error));
    if (label) {
        label.textContent = online ? "本地服务已连接" : "本地服务未连接";
    }
    if (detail) {
        detail.textContent = online
            ? `沙箱：${state.health?.workspace ?? ".orderly-data/workspace"}`
            : "请确认 Java 宿主已启动";
    }
    if (planner) {
        planner.textContent = String(state.health?.planner ?? "LOCAL_RULES") === "LOCAL_RULES"
            ? "本地规则计划器"
            : String(state.health?.planner ?? "计划器状态未知");
    }
}

/**
 * 渲染侧边栏导航，并应用搜索和模块显示偏好。
 *
 * @param {string} query 搜索词
 */
function renderNavigation(query = "") {
    const container = document.querySelector("#primary-nav");
    if (!container) {
        return;
    }
    const normalizedQuery = String(query).trim().toLocaleLowerCase("zh-CN");
    const settings = settingsStore.getSnapshot();
    const moduleItems = state.modules
        .filter((module) => !isModuleHidden(settings, module.moduleId))
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
            if (item.moduleId) {
                button.dataset.moduleId = item.moduleId;
            }
            button.innerHTML = `<span class="nav-icon">${icon(item.icon)}</span>`;
            const copy = element("span", "nav-copy");
            copy.append(
                element("strong", "", item.label),
                element("small", "", item.caption)
            );
            button.append(copy);
            const count = navigationCount(item.id);
            if (count != null) {
                button.append(element("span", "nav-count", String(count)));
            } else {
                button.append(element("span", "nav-count", "·"));
            }
            button.addEventListener("click", () => {
                void navigate(item.id);
            });
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
    return state.modules.find((module) => moduleNavigationItem(module).id === view) ?? null;
}

/**
 * 获取导航计数。
 *
 * @param {string} view 视图 id
 * @returns {number | null} 数量
 */
function navigationCount(view) {
    const module = state.modules.find((item) => moduleNavigationItem(item).id === view);
    return module ? state.moduleCounts[module.moduleId] ?? null : null;
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
    const breadcrumb = document.querySelector("#breadcrumb");
    if (breadcrumb) {
        breadcrumb.replaceChildren(
            element("span", "", "工作台"),
            element("span", "", "/"),
            element("strong", "", selectedModule?.title ?? labels[view] ?? "模块")
        );
    }

    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
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
        await moduleScript.default(root, {
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
        });
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
        return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
        const style = document.createElement("link");
        style.rel = "stylesheet";
        style.href = url;
        style.dataset.orderlyModuleStyle = module.moduleId;
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
        <div class="view-stack">
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
    const jobs = state.modules.map(async (module) => {
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
    const visible = ordered.filter((module) => !isModuleHidden(settings, module.moduleId));
    grid.replaceChildren();
    if (orbit) {
        orbit.replaceChildren();
        ordered.slice(0, 3).forEach((module, index) => {
            const ui = state.moduleUi[module.moduleId];
            const node = element("span", `orbit-node orbit-node--slot-${index + 1}`, "");
            node.innerHTML = icon(ui?.view.icon ?? "modules", 22);
            orbit.append(node);
        });
    }
    if (!visible.length) {
        grid.append(element(
            "div",
            "panel empty-state module-empty-state",
            state.modules.length ? "所有模块已在设置中隐藏。" : "尚未安装功能模块；添加 Module/module_* 并重新构建后会显示在这里。"
        ));
        return;
    }

    visible.forEach((module, index) => {
        const ui = state.moduleUi[module.moduleId];
        const card = ui?.card ?? {
            title: module.title || module.moduleId,
            description: state.moduleErrors[module.moduleId] || module.description || "模块摘要不可用。",
            badge: "模块界面异常",
            icon: ui?.view.icon ?? "modules",
            unit: "项"
        };
        const article = element("article", "surface-card module-card");
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
        down.disabled = index === visible.length - 1;
        down.setAttribute("aria-label", `下移 ${card.title}`);
        down.addEventListener("click", () => changeModuleOrder(module.moduleId, 1));
        controls.append(up, down);
        article.append(top, body, controls);
        article.addEventListener("dragstart", (event) => {
            state.dragModuleId = module.moduleId;
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
    state.modules = sortModules(state.modules, moduleOrderIds);
    const status = document.querySelector("#module-order-status");
    if (status) status.textContent = message;
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    if (state.activeView === "overview") renderModuleCards();
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
    document.querySelector("#dock-toggle")?.setAttribute("aria-expanded", "true");
}

/**
 * 渲染快捷示例。
 */
function renderCommandExamples() {
    const container = document.querySelector("#command-examples");
    const examples = state.modules
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
 * 打开移动端侧边栏。
 */
function openMobileNav() {
    document.querySelector(".sidebar")?.classList.add("is-open");
    const scrim = document.querySelector("#mobile-scrim");
    if (scrim) {
        scrim.hidden = false;
    }
}

/**
 * 关闭移动端侧边栏。
 */
function closeMobileNav() {
    document.querySelector(".sidebar")?.classList.remove("is-open");
    const scrim = document.querySelector("#mobile-scrim");
    if (scrim) {
        scrim.hidden = true;
    }
}

/**
 * 打开设置。
 */
function openSettings() {
    const dialog = document.querySelector("#settings-dialog");
    if (dialog && !dialog.open) {
        renderSettings();
        dialog.showModal();
    }
}

/**
 * 绑定设置弹窗。
 */
function bindSettingsDialog() {
    const dialog = document.querySelector("#settings-dialog");
    document.querySelector("#close-settings")?.addEventListener("click", () => dialog?.close());
    document.querySelector("#reset-settings")?.addEventListener("click", () => {
        settingsStore.reset();
        toast("设置已恢复默认。");
    });
    dialog?.addEventListener("click", (event) => {
        if (event.target === dialog) {
            dialog.close();
        }
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
function renderSettings() {
    const nav = document.querySelector("#settings-nav");
    const panel = document.querySelector("#settings-panel");
    if (!nav || !panel) {
        return;
    }
    const sections = [
        { id: "appearance", label: "外观", note: "主题与动效", icon: "palette" },
        { id: "modules", label: "模块管理", note: "导航可见性", icon: "modules" },
        { id: "system", label: "系统设置", note: "本地运行边界", icon: "shield" }
    ];
    nav.replaceChildren();
    sections.forEach((section) => {
        const button = element(
            "button",
            `settings-nav__item${state.settingsSection === section.id ? " is-active" : ""}`
        );
        button.type = "button";
        button.innerHTML = `<i>${icon(section.icon, 18)}</i>`;
        const copy = element("span");
        copy.append(
            element("strong", "", section.label),
            element("small", "", section.note)
        );
        button.append(copy);
        button.addEventListener("click", () => {
            state.settingsSection = section.id;
            renderSettings();
        });
        nav.append(button);
    });

    panel.replaceChildren();
    if (state.settingsSection === "appearance") {
        renderAppearanceSettings(panel);
    } else if (state.settingsSection === "modules") {
        renderModuleSettings(panel);
    } else {
        renderSystemSettings(panel);
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
        element("p", "", "主题只替换语义变量；动效关闭后，状态与内容仍然完整可见。")
    );
    panel.append(header);

    const fields = element("div", "settings-fields");
    const themeField = element("fieldset", "setting-field");
    themeField.append(
        element("legend", "", "界面主题"),
        element("p", "", "主题由 /api/themes 动态提供，组件不保存静态主题列表。")
    );
    const themeGrid = element("div", "theme-grid");
    const themes = [...state.themes].sort((left, right) =>
        Number(left.order ?? 0) - Number(right.order ?? 0)
            || String(left.id).localeCompare(String(right.id)));
    if (themes.length === 0) {
        themeGrid.append(element("p", "empty-state", "未发现可用主题，界面保留基础 CSS 变量。"));
    }
    themes.forEach((option) => {
        const label = element("label", "theme-option");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "theme";
        input.value = option.id;
        input.checked = settings.theme === option.id;
        const card = element("span", "theme-option__card");
        const swatch = element("span", "theme-swatch");
        const first = document.createElement("i");
        const second = document.createElement("i");
        const third = document.createElement("i");
        first.style.background = "var(--color-canvas-deep)";
        second.style.background = "var(--color-accent)";
        third.style.background = "var(--color-secondary)";
        swatch.append(first, second, third);
        card.append(
            swatch,
            element("strong", "", option.label || option.id),
            element("small", "muted", option.note || (option.default ? "默认主题" : "本地主题"))
        );
        label.append(input, card);
        input.addEventListener("change", () => {
            if (input.checked) {
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
 * 渲染模块可见性设置。
 *
 * @param {HTMLElement} panel 面板
 */
function renderModuleSettings(panel) {
    const settings = settingsStore.getSnapshot();
    const header = element("header", "settings-panel__header");
    header.append(
        element("p", "eyebrow", "MODULES"),
        element("h3", "", "模块管理"),
        element("p", "", "这里只控制导航可见性；模块执行器仍由宿主契约统一注册与担保。")
    );
    panel.append(header);
    const fields = element("div", "settings-fields settings-fields--modules");
    if (state.modules.length === 0) {
        fields.append(element("p", "empty-state", "当前没有已安装模块。安装本地模块并重启宿主后会显示在这里。"));
    }
    state.modules.forEach((module) => {
        const hidden = isModuleHidden(settings, module.moduleId);
        const row = element("div", "setting-field setting-field--row");
        const copy = element("div");
        copy.append(
            element("label", "", module.title || module.moduleId),
            element("p", "", module.description || "由模块契约声明的功能。")
        );
        row.append(copy, switchControl(
            module.moduleId,
            !hidden,
            (visible) => {
                const next = setModuleHidden(settingsStore.getSnapshot(), module.moduleId, !visible);
                settingsStore.update({ hiddenModuleIds: next.hiddenModuleIds });
            }
        ));
        fields.append(row);
    });
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
        ["文件边界", state.health?.workspace ?? ".orderly-data/workspace"],
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
