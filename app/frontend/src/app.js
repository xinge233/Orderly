import { ApiError, OrderlyApi } from "./api.js";
import { hydrateIcons, icon } from "./icons.js";
import {
    RISK_LABEL,
    flattenText,
    formatBytes,
    formatDate,
    isDirectoryEntry,
    joinPath,
    mergeEvents,
    normalizeEvent,
    normalizePlan,
    parentPath,
    requiresDeletePhrase,
    statusLabel,
    unwrapList,
    validateConfirmation
} from "./models.js";
import {
    MOTION_OPTIONS,
    SettingsStore,
    THEME_OPTIONS,
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
            { id: "overview", label: "总览", caption: "计划与执行", icon: "home" },
            { id: "files", label: "文件与目录", caption: "浏览与整理", icon: "folder", module: "files" },
            { id: "todos", label: "待办事项", caption: "任务收束", icon: "check", module: "todos" },
            { id: "calendar", label: "日程安排", caption: "时间视图", icon: "calendar", module: "calendar" }
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

const EXAMPLES = Object.freeze([
    {
        label: "归档示例",
        text: "创建目录 归档，然后移动 收件箱/示例笔记.txt 到 归档"
    },
    {
        label: "待办示例",
        text: "创建待办：提交周报；截止：2026-11-20"
    },
    {
        label: "日程示例",
        text: "把日程 项目评审 改到 2026-11-20 15:00"
    },
    {
        label: "删除示例",
        text: "删除 待整理/草案.txt"
    }
]);

const state = {
    activeView: "overview",
    health: null,
    plan: null,
    planBusy: false,
    executing: false,
    confirmed: false,
    deletePhrase: "",
    currentRun: null,
    events: [],
    filePath: "",
    settingsSection: "appearance",
    overviewData: {
        files: [],
        todos: [],
        calendar: []
    }
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
    renderCommandExamples();
    applySettings(settingsStore.getSnapshot());
    settingsStore.subscribe(applySettings);
    renderSettings();

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

    void navigate("overview");
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
    void themeManager.apply(settings.theme);
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
    renderSettings();
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
    const moduleVisibility = {
        files: settings.showFileModule,
        todos: settings.showTodoModule,
        calendar: settings.showCalendarModule
    };
    container.replaceChildren();

    NAV_SECTIONS.forEach((section) => {
        const visibleItems = section.items.filter((item) => {
            if (item.module && !moduleVisibility[item.module]) {
                return false;
            }
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
        container.append(group);
    });

    if (!container.childElementCount) {
        container.append(element("p", "empty-state", "没有匹配的功能"));
    }
}

/**
 * 获取导航计数。
 *
 * @param {string} view 视图 id
 * @returns {number | null} 数量
 */
function navigationCount(view) {
    if (view === "files") {
        return state.overviewData.files.length || null;
    }
    if (view === "todos") {
        return state.overviewData.todos.length || null;
    }
    if (view === "calendar") {
        return state.overviewData.calendar.length || null;
    }
    return null;
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
    const labels = {
        overview: "总览",
        files: "文件与目录",
        todos: "待办事项",
        calendar: "日程安排",
        history: "执行记录"
    };
    const breadcrumb = document.querySelector("#breadcrumb");
    if (breadcrumb) {
        breadcrumb.replaceChildren(
            element("span", "", "工作台"),
            element("span", "", "/"),
            element("strong", "", labels[view] ?? "总览")
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
            <p>加载 ${labels[view] ?? "工作台"}…</p>
        </section>
    `;

    try {
        if (view === "files") {
            await loadFiles(state.filePath, false);
        } else if (view === "todos") {
            await loadTodos();
        } else if (view === "calendar") {
            await loadCalendar();
        } else if (view === "history") {
            await loadHistory();
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
 * 加载总览与三类数量。
 */
async function loadOverview() {
    const results = await Promise.allSettled([
        api.getFiles(),
        api.getTodos(),
        api.getCalendar()
    ]);
    state.overviewData.files = results[0].status === "fulfilled"
        ? unwrapList(results[0].value, ["entries", "files"])
        : [];
    state.overviewData.todos = results[1].status === "fulfilled"
        ? unwrapList(results[1].value, ["todos"])
        : [];
    state.overviewData.calendar = results[2].status === "fulfilled"
        ? unwrapList(results[2].value, ["calendar", "events"])
        : [];
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
                        文件、待办与日程在一条可审查的流程中协作。
                        本版本只操作应用自管沙箱，先生成计划，确认之后才会写入。
                    </p>
                    <div class="hero-tags">
                        <span>${icon("shield")} 本地优先</span>
                        <span>${icon("route")} 计划先于执行</span>
                        <span>${icon("history")} 回收站与回滚</span>
                    </div>
                </div>
                <div class="hero-diagram" aria-label="文件、待办与日程汇聚到归序">
                    <span class="orbit-ring"></span>
                    <span class="orbit-core"><strong>归序</strong><small>工作流中枢</small></span>
                    <span class="orbit-node orbit-node--files">${icon("folder", 22)}</span>
                    <span class="orbit-node orbit-node--todos">${icon("check", 22)}</span>
                    <span class="orbit-node orbit-node--calendar">${icon("calendar", 22)}</span>
                </div>
            </section>

            <section class="surface-grid" aria-label="本地模块摘要">
                <article class="surface-card">
                    <div class="surface-card__top">
                        <span class="surface-card__icon">${icon("folder", 21)}</span>
                        <span class="status-chip">真实本地文件</span>
                    </div>
                    <div>
                        <div class="surface-card__metric">
                            <strong data-count="files">—</strong><small>个顶层条目</small>
                        </div>
                        <p>浏览沙箱目录；移动、删除等动作必须经过计划确认。</p>
                    </div>
                </article>
                <article class="surface-card">
                    <div class="surface-card__top">
                        <span class="surface-card__icon">${icon("check", 21)}</span>
                        <span class="status-chip">可回滚</span>
                    </div>
                    <div>
                        <div class="surface-card__metric">
                            <strong data-count="todos">—</strong><small>项待办</small>
                        </div>
                        <p>待办创建由框架统一记录事件，并保留创建前状态。</p>
                    </div>
                </article>
                <article class="surface-card">
                    <div class="surface-card__top">
                        <span class="surface-card__icon">${icon("calendar", 21)}</span>
                        <span class="status-chip">本地存储</span>
                    </div>
                    <div>
                        <div class="surface-card__metric">
                            <strong data-count="calendar">—</strong><small>项日程</small>
                        </div>
                        <p>改期前后版本都会写入审计事件，可整单撤销。</p>
                    </div>
                </article>
            </section>

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

    const counts = {
        files: state.overviewData.files.length,
        todos: state.overviewData.todos.length,
        calendar: state.overviewData.calendar.length
    };
    Object.entries(counts).forEach(([key, value]) => {
        setText(main, `[data-count="${key}"]`, String(value));
    });
    hydrateIcons(main);
    renderPlan();
    renderTimeline();
    renderRunSummary();
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
        empty.append(
            signal,
            element("h3", "", "从右下角告诉归序你想完成什么"),
            element(
                "p",
                "",
                "尝试创建目录、移动文件、创建待办、修改日程或删除文件。"
                + "宿主会先返回步骤与风险，不会立即执行。"
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
 * 只刷新总览计数，不替换正在阅读的计划卡。
 */
async function loadOverviewCounts() {
    const results = await Promise.allSettled([
        api.getFiles(),
        api.getTodos(),
        api.getCalendar()
    ]);
    if (results[0].status === "fulfilled") {
        state.overviewData.files = unwrapList(results[0].value, ["entries", "files"]);
    }
    if (results[1].status === "fulfilled") {
        state.overviewData.todos = unwrapList(results[1].value, ["todos"]);
    }
    if (results[2].status === "fulfilled") {
        state.overviewData.calendar = unwrapList(results[2].value, ["calendar", "events"]);
    }
    Object.entries({
        files: state.overviewData.files.length,
        todos: state.overviewData.todos.length,
        calendar: state.overviewData.calendar.length
    }).forEach(([key, value]) => {
        setText(document, `[data-count="${key}"]`, String(value));
    });
    renderNavigation(document.querySelector("#nav-search")?.value ?? "");
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
        const time = element(
            "span",
            "timeline-time",
            event.timestamp ? formatDate(event.timestamp) : "实时"
        );
        item.append(time);
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
        const mapping = {
            module_fs: "files",
            fs: "files",
            module_todo: "todos",
            todo: "todos",
            module_calendar: "calendar",
            calendar: "calendar"
        };
        const target = [...modules].some((moduleId) => mapping[moduleId] === button.dataset.view);
        button.classList.toggle("is-plan-target", target);
        if (target) {
            window.setTimeout(() => button.classList.remove("is-plan-target"), 2600);
        }
    });
}

/**
 * 加载文件视图。
 *
 * @param {string} path 相对路径
 * @param {boolean} updateHistory 是否更新当前路径
 */
async function loadFiles(path = "", updateHistory = true) {
    if (updateHistory) {
        state.filePath = path;
    }
    const payload = await api.getFiles(path);
    const entries = unwrapList(payload, ["entries", "files"]).sort((left, right) => {
        const directoryOrder = Number(isDirectoryEntry(right)) - Number(isDirectoryEntry(left));
        return directoryOrder || String(left?.name ?? "").localeCompare(
            String(right?.name ?? ""),
            "zh-CN"
        );
    });
    const responsePath = String(
        payload && typeof payload === "object" && payload.path != null
            ? payload.path
            : path
    );
    state.filePath = responsePath;
    renderFiles(entries, payload);
}

/**
 * 渲染文件列表。
 *
 * @param {Array<object>} entries 条目
 * @param {unknown} payload 原始响应
 */
function renderFiles(entries, payload) {
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.innerHTML = `
        <div class="view-stack">
            <header class="page-heading">
                <span class="page-heading__icon">${icon("folder", 25)}</span>
                <div>
                    <p class="eyebrow">FILE MODULE</p>
                    <h1>文件与目录</h1>
                    <p>仅访问应用自管沙箱。目录浏览是只读；整理动作先进入计划预览。</p>
                </div>
                <span class="status-chip">本地沙箱</span>
            </header>
            <div class="data-toolbar">
                <div class="path-trail">
                    <span>workspace /</span>
                    <strong id="file-path"></strong>
                </div>
                <div class="entity-actions">
                    <button class="secondary-button" id="file-root" type="button">工作区根目录</button>
                    <button class="secondary-button" id="file-refresh" type="button">${icon("refresh", 16)}刷新</button>
                </div>
            </div>
            <section class="data-card">
                <header class="panel__header">
                    <div>
                        <h2>目录条目</h2>
                        <p id="file-summary">正在读取…</p>
                    </div>
                </header>
                <div id="file-list"></div>
            </section>
        </div>
    `;
    hydrateIcons(main);
    setText(main, "#file-path", state.filePath || "根目录");
    setText(main, "#file-summary", `${entries.length} 个条目 · 实际读取自 /api/files`);
    const list = main.querySelector("#file-list");
    if (!entries.length) {
        list.append(
            emptyState(
                "目录为空",
                "可以通过右下角生成“创建目录”计划，或把文件放入应用沙箱。"
            )
        );
    } else {
        const rows = element("div", "entity-list");
        entries.forEach((entry) => renderFileRow(rows, entry));
        list.append(rows);
    }

    main.querySelector("#file-root")?.addEventListener("click", () => {
        void loadFiles("").catch((error) => toast(errorMessage(error), { error: true }));
    });
    main.querySelector("#file-refresh")?.addEventListener("click", () => {
        void loadFiles(state.filePath).catch((error) => toast(errorMessage(error), { error: true }));
    });

    const parent = payload && typeof payload === "object" ? payload.parentPath : null;
    if (state.filePath) {
        const up = element("button", "secondary-button", `返回 ${parent || "上一级" || "根目录"}`);
        up.type = "button";
        up.addEventListener("click", () => {
            void loadFiles(String(parent ?? parentPath(state.filePath))).catch((error) => {
                toast(errorMessage(error), { error: true });
            });
        });
        main.querySelector(".entity-actions")?.prepend(up);
    }
}

/**
 * 渲染文件行。
 *
 * @param {HTMLElement} container 容器
 * @param {object} entry 条目
 */
function renderFileRow(container, entry) {
    const directory = isDirectoryEntry(entry);
    const name = String(entry?.name ?? entry?.path ?? "未命名条目");
    const fullPath = String(entry?.path ?? joinPath(state.filePath, name));
    const row = element("div", "entity-row");
    const entityIcon = element("span", "entity-icon");
    entityIcon.innerHTML = icon(directory ? "folder" : "file", 20);
    const copy = element("span", "entity-copy");
    copy.append(
        element("strong", "", name),
        element(
            "small",
            "",
            directory
                ? `目录 · ${fullPath || "根目录"}`
                : `${formatBytes(entry?.size)} · ${entry?.modifiedAt ? formatDate(entry.modifiedAt) : fullPath}`
        )
    );
    row.append(entityIcon, copy);

    const actions = element("span", "entity-actions");
    if (directory) {
        const open = element("button", "secondary-button", "打开");
        open.type = "button";
        open.addEventListener("click", () => {
            void loadFiles(fullPath).catch((error) => toast(errorMessage(error), { error: true }));
        });
        actions.append(open);
    } else {
        const reference = element("button", "secondary-button", "引用到移动示例");
        reference.type = "button";
        reference.addEventListener("click", () => fillCommand(`移动 ${fullPath} 到 归档`));
        const trash = element("button", "text-button", "移入回收站");
        trash.type = "button";
        trash.addEventListener("click", () => fillCommand(`删除 ${fullPath}`));
        actions.append(reference, trash);
    }
    row.append(actions);
    container.append(row);
}

/**
 * 加载待办。
 */
async function loadTodos() {
    const payload = await api.getTodos();
    const todos = unwrapList(payload, ["todos"]).filter((item) => item && typeof item === "object");
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.innerHTML = `
        <div class="view-stack">
            <header class="page-heading">
                <span class="page-heading__icon">${icon("check", 25)}</span>
                <div>
                    <p class="eyebrow">TODO MODULE</p>
                    <h1>待办事项</h1>
                    <p>待办由本地模块保存；新增动作需要先生成计划并确认。</p>
                </div>
                <span class="status-chip">可逆写入</span>
            </header>
            <section class="data-card">
                <header class="panel__header">
                    <div>
                        <h2>当前待办</h2>
                        <p id="todo-summary"></p>
                    </div>
                    <button class="secondary-button" id="todo-example" type="button">填入创建示例</button>
                </header>
                <div id="todo-list"></div>
            </section>
        </div>
    `;
    hydrateIcons(main);
    setText(main, "#todo-summary", `${todos.length} 项 · 实际读取自 /api/todos`);
    const list = main.querySelector("#todo-list");
    if (!todos.length) {
        list.append(emptyState("暂无待办", "使用“创建待办：提交周报；截止：2026-11-20”生成第一项。"));
    } else {
        const rows = element("div", "entity-list");
        todos.forEach((todo) => {
            const row = element("div", "entity-row");
            const todoIcon = element("span", "entity-icon");
            todoIcon.innerHTML = icon("check", 20);
            const copy = element("span", "entity-copy");
            copy.append(
                element("strong", "", String(todo.title ?? todo.name ?? "未命名待办")),
                element(
                    "small",
                    "",
                    `${statusLabel(todo.status ?? "OPEN")} · 截止 ${formatDate(todo.dueAt ?? todo.due ?? todo.deadline)}`
                )
            );
            row.append(todoIcon, copy, element("span", "entity-time", todo.id ? `#${todo.id}` : ""));
            rows.append(row);
        });
        list.append(rows);
    }
    main.querySelector("#todo-example")?.addEventListener("click", () => {
        fillCommand("创建待办：提交周报；截止：2026-11-20");
    });
}

/**
 * 加载日程。
 */
async function loadCalendar() {
    const payload = await api.getCalendar();
    const events = unwrapList(payload, ["calendar", "events"])
        .filter((item) => item && typeof item === "object");
    const main = document.querySelector("#main-content");
    if (!main) {
        return;
    }
    main.innerHTML = `
        <div class="view-stack">
            <header class="page-heading">
                <span class="page-heading__icon">${icon("calendar", 25)}</span>
                <div>
                    <p class="eyebrow">CALENDAR MODULE</p>
                    <h1>日程安排</h1>
                    <p>查看本地日程；修改时间会受到计划确认和事件审计保护。</p>
                </div>
                <span class="status-chip">本地存储</span>
            </header>
            <section class="data-card">
                <header class="panel__header">
                    <div>
                        <h2>日程列表</h2>
                        <p id="calendar-summary"></p>
                    </div>
                    <button class="secondary-button" id="calendar-example" type="button">填入改期示例</button>
                </header>
                <div id="calendar-list"></div>
            </section>
        </div>
    `;
    hydrateIcons(main);
    setText(main, "#calendar-summary", `${events.length} 项 · 实际读取自 /api/calendar`);
    const list = main.querySelector("#calendar-list");
    if (!events.length) {
        list.append(emptyState("暂无日程", "使用“把日程 项目评审 改到 2026-11-20 15:00”修改种子日程。"));
    } else {
        const rows = element("div", "entity-list");
        events.forEach((event) => {
            const row = element("div", "entity-row");
            const eventIcon = element("span", "entity-icon");
            eventIcon.innerHTML = icon("calendar", 20);
            const copy = element("span", "entity-copy");
            copy.append(
                element("strong", "", String(event.title ?? event.name ?? "未命名日程")),
                element(
                    "small",
                    "",
                    `${formatDate(event.startAt ?? event.start ?? event.time)}`
                    + `${event.endAt || event.end ? ` → ${formatDate(event.endAt ?? event.end)}` : ""}`
                )
            );
            row.append(eventIcon, copy, element(
                "span",
                "entity-time",
                event.location ? String(event.location) : "本地日程"
            ));
            rows.append(row);
        });
        list.append(rows);
    }
    main.querySelector("#calendar-example")?.addEventListener("click", () => {
        fillCommand("把日程 项目评审 改到 2026-11-20 15:00");
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
    if (!container) {
        return;
    }
    EXAMPLES.forEach((example) => {
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
        element("p", "", "颜色来自 themes/*.json，组件不保存具体色值。")
    );
    const themeGrid = element("div", "theme-grid");
    THEME_OPTIONS.forEach((option) => {
        const label = element("label", "theme-option");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "theme";
        input.value = option.value;
        input.checked = settings.theme === option.value;
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
            element("strong", "", option.label),
            element("small", "muted", option.note)
        );
        label.append(input, card);
        input.addEventListener("change", () => {
            if (input.checked) {
                settingsStore.update({ theme: option.value });
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
    const fields = element("div", "settings-fields");
    [
        ["showFileModule", "文件与目录", "列目录、建目录、移动与回收站。"],
        ["showTodoModule", "待办事项", "创建可回滚的本地待办。"],
        ["showCalendarModule", "日程安排", "查看并修改本地日程时间。"]
    ].forEach(([key, title, description]) => {
        fields.append(settingRow(key, title, description, settings[key]));
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
function switchControl(key, checked) {
    const label = element("label", "switch");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = checked;
    input.setAttribute("aria-label", key);
    input.addEventListener("change", () => {
        settingsStore.update({ [key]: input.checked });
    });
    label.append(input, element("span"));
    return label;
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap, { once: true });
} else {
    void bootstrap();
}
