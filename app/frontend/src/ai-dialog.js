import { icon } from "./icons.js";

const AI_RISK_ORDER = Object.freeze({
    READ_ONLY: 0,
    WRITE: 1,
    DELETE: 2
});

const AI_RISK_LABEL = Object.freeze({
    READ_ONLY: "只读",
    WRITE: "写入",
    DELETE: "删除"
});

const AI_PROGRESS_HIDE_DELAY_MS = 140;
const AI_RUNNING_LEAVE_DELAY_MS = 120;
const AI_COMPLETION_HOLD_MS = 2800;
const AI_STEP_GAP_MS = 220;

/**
 * 选择更高的风险等级。
 *
 * @param {string} left 风险等级
 * @param {string} right 风险等级
 * @returns {"READ_ONLY" | "WRITE" | "DELETE"} 较高风险
 */
export function higherAiRisk(left, right) {
    return (AI_RISK_ORDER[right] ?? 0) > (AI_RISK_ORDER[left] ?? 0)
        ? right
        : left;
}

/**
 * 构造仅用于界面演示的计划。此函数不会调用宿主或执行任何动作。
 *
 * @param {string} goal 用户输入
 * @param {Array<object>} modules 可见业务模块
 * @returns {object} 演示计划
 */
export function createAiDemoPlan(goal, modules = []) {
    const visibleModules = modules
        .filter((module) => module?.moduleId && module.enabled !== false)
        .slice(0, 3);
    const riskPattern = ["READ_ONLY", "WRITE", "READ_ONLY"];
    const verbs = ["读取", "更新", "检查"];
    const actions = visibleModules.length
        ? visibleModules.map((module, index) => ({
            id: `ai-demo-${index + 1}`,
            moduleId: module.moduleId,
            moduleTitle: module.title || module.moduleId,
            title: `${verbs[index] ?? "检查"}「${module.title || module.moduleId}」`,
            risk: riskPattern[index] ?? "READ_ONLY",
            status: "pending"
        }))
        : [{
            id: "ai-demo-target-unavailable",
            moduleId: "",
            moduleTitle: "",
            title: "检查当前工作区",
            risk: "READ_ONLY",
            status: "pending"
        }];
    const risk = actions.reduce(
        (highest, action) => higherAiRisk(highest, action.risk),
        "READ_ONLY"
    );

    return {
        id: `ai-demo-${Date.now()}`,
        title: String(goal ?? "").trim() || "整理当前工作区",
        risk,
        actions
    };
}

/**
 * 挂载 AI 对话框。组件只负责界面状态、演示进度和调用轨迹。
 *
 * @param {object} options 配置
 * @param {HTMLElement} options.dock AI Dock 根节点
 * @param {HTMLElement} options.callLayer 轨迹覆盖层
 * @param {() => Array<object>} options.getModules 可见模块提供器
 * @param {(message: string, options?: object) => void} options.onNotice 通知回调
 * @returns {{setEnabled: (enabled: boolean) => void, reset: () => void, destroy: () => void}}
 */
export function mountAiDialog({
    dock,
    callLayer,
    getModules,
    onNotice = () => {}
}) {
    if (!dock || !callLayer) {
        throw new Error("AI 对话框缺少必要挂载节点。");
    }

    const nodes = {
        launcher: dock.querySelector("#ai-launcher"),
        launcherIcon: dock.querySelector("#ai-launcher-icon"),
        composer: dock.querySelector("#ai-composer"),
        composerForm: dock.querySelector("#ai-composer-form"),
        composerInput: dock.querySelector("#ai-composer-input"),
        composerClose: dock.querySelector("#ai-composer-close"),
        plan: dock.querySelector("#ai-plan-panel"),
        planClose: dock.querySelector("#ai-plan-close"),
        planTitle: dock.querySelector("#ai-plan-title"),
        planList: dock.querySelector("#ai-plan-list"),
        risk: dock.querySelector("#ai-risk-indicator"),
        run: dock.querySelector("#ai-run-button"),
        progress: dock.querySelector("#ai-progress-popover"),
        progressTitle: dock.querySelector("#ai-progress-title"),
        progressList: dock.querySelector("#ai-progress-list")
    };
    const missing = Object.entries(nodes).find(([, node]) => !node);
    if (missing) {
        throw new Error(`AI 对话框缺少节点：${missing[0]}`);
    }

    const timers = new Set();
    const invocationCleanups = new Set();
    const dialog = {
        enabled: false,
        mode: "collapsed",
        panelOpen: false,
        popoverOpen: false,
        plan: null,
        runGeneration: 0,
        popoverTimer: null,
        collapseTimer: null,
        completionTimer: null
    };

    function later(callback, delay) {
        const timer = window.setTimeout(() => {
            timers.delete(timer);
            callback();
        }, delay);
        timers.add(timer);
        return timer;
    }

    function clearLater(timer) {
        if (timer != null) {
            window.clearTimeout(timer);
            timers.delete(timer);
        }
        return null;
    }

    function clearAllTimers() {
        for (const timer of timers) {
            window.clearTimeout(timer);
        }
        timers.clear();
        dialog.popoverTimer = null;
        dialog.collapseTimer = null;
        dialog.completionTimer = null;
    }

    function clearInvocationLayer() {
        for (const cleanup of invocationCleanups) {
            cleanup();
        }
        invocationCleanups.clear();
        callLayer.replaceChildren();
    }

    function updateSurfaces() {
        const composerOpen = dialog.mode === "composing";
        const planOpen = dialog.panelOpen
            && ["planning", "running", "completed"].includes(dialog.mode);
        const launcherVisible = !composerOpen && !planOpen;
        const running = dialog.mode === "running";
        const completed = dialog.mode === "completed";

        dock.dataset.mode = dialog.mode;
        dock.dataset.panel = planOpen ? "open" : "closed";
        dock.dataset.popover = dialog.popoverOpen ? "open" : "closed";
        dock.dataset.demo = "true";

        nodes.composer.setAttribute("aria-hidden", String(!composerOpen));
        nodes.composer.inert = !composerOpen;
        nodes.plan.setAttribute("aria-hidden", String(!planOpen));
        nodes.plan.inert = !planOpen;
        nodes.launcher.setAttribute("aria-hidden", String(!launcherVisible));
        nodes.launcher.inert = !launcherVisible;
        nodes.launcher.setAttribute("aria-expanded", String(planOpen));
        nodes.progress.setAttribute("aria-hidden", String(!dialog.popoverOpen));

        nodes.launcher.dataset.status = running
            ? "running"
            : completed
                ? "completed"
                : "idle";
        nodes.launcherIcon.innerHTML = icon(
            completed ? "check-plain" : running ? "spark" : "spark",
            21
        );
        nodes.launcher.setAttribute(
            "aria-label",
            running
                ? "AI 界面演示正在运行，查看执行进度"
                : completed
                    ? "AI 界面演示已完成，查看完整计划"
                    : "打开 AI 对话"
        );
        nodes.planClose.setAttribute(
            "aria-label",
            running ? "收起运行中的计划" : "关闭计划"
        );
        nodes.run.disabled = dialog.mode === "running" || !dialog.plan;
        nodes.run.innerHTML = `${icon("check-plain", 15)}<span>执行</span>`;
    }

    function riskLabel(risk) {
        return AI_RISK_LABEL[risk] ?? AI_RISK_LABEL.READ_ONLY;
    }

    function statusLabel(status) {
        if (status === "running") return "运行中";
        if (status === "completed") return "演示完成";
        return "等待";
    }

    function renderPlan() {
        if (!dialog.plan) {
            nodes.planTitle.textContent = "尚未生成计划";
            nodes.planList.replaceChildren();
            nodes.risk.dataset.risk = "READ_ONLY";
            nodes.risk.innerHTML = `${icon("shield", 14)}<span>风险等级：只读</span>`;
            updateSurfaces();
            return;
        }

        nodes.planTitle.textContent = dialog.plan.title;
        nodes.planList.replaceChildren();
        dialog.plan.actions.forEach((action, index) => {
            const item = document.createElement("li");
            item.className = "ai-plan-step";
            item.dataset.status = action.status;

            const indexNode = document.createElement("span");
            indexNode.className = "ai-plan-step__index";
            indexNode.textContent = String(index + 1).padStart(2, "0");

            const copy = document.createElement("span");
            copy.className = "ai-plan-step__copy";
            const title = document.createElement("strong");
            title.textContent = action.title;
            const meta = document.createElement("small");
            meta.textContent = `${action.moduleTitle || "目标模块不可见"} · ${riskLabel(action.risk)}`;
            copy.append(title, meta);

            const status = document.createElement("span");
            status.className = "ai-plan-step__status";
            status.dataset.status = action.status;
            if (action.status === "completed") {
                status.innerHTML = icon("check-plain", 14);
                status.setAttribute("aria-label", "演示完成");
            } else {
                status.textContent = statusLabel(action.status);
            }

            const risk = document.createElement("span");
            risk.className = "ai-risk-chip";
            risk.dataset.risk = action.risk;
            risk.textContent = riskLabel(action.risk);

            item.append(indexNode, copy, status, risk);
            nodes.planList.append(item);
        });

        nodes.risk.dataset.risk = dialog.plan.risk;
        nodes.risk.innerHTML = `${icon("alert", 14)}<span>风险等级：${riskLabel(dialog.plan.risk)}</span>`;
        updateSurfaces();
    }

    function renderProgress() {
        if (!dialog.plan) {
            nodes.progressTitle.textContent = "界面演示 · 未接入 AI";
            nodes.progressList.replaceChildren();
            return;
        }
        const completed = dialog.plan.actions.filter(
            (action) => action.status === "completed"
        ).length;
        nodes.progressTitle.textContent = dialog.mode === "completed"
            ? `演示完成 · ${completed}/${dialog.plan.actions.length}`
            : `运行进度 · ${completed}/${dialog.plan.actions.length}`;
        nodes.progressList.replaceChildren();
        dialog.plan.actions.forEach((action) => {
            const item = document.createElement("li");
            item.dataset.status = action.status;
            const copy = document.createElement("span");
            copy.className = "ai-progress-copy";
            const title = document.createElement("strong");
            title.textContent = action.title;
            const meta = document.createElement("small");
            meta.textContent = `${action.moduleTitle || "目标模块不可见"} · ${statusLabel(action.status)}`;
            copy.append(title, meta);
            const state = document.createElement("span");
            state.className = "ai-progress-state";
            if (action.status === "completed") {
                state.innerHTML = icon("check-plain", 15);
                state.setAttribute("aria-label", "演示完成");
            } else {
                state.textContent = statusLabel(action.status);
            }
            item.append(copy, state);
            nodes.progressList.append(item);
        });
    }

    function renderAll() {
        renderPlan();
        renderProgress();
        updateSurfaces();
    }

    function setMode(mode, { panelOpen = false, focus = null } = {}) {
        dialog.mode = mode;
        dialog.panelOpen = panelOpen;
        if (!["running", "completed"].includes(mode)) {
            dialog.popoverOpen = false;
        }
        renderAll();
        if (focus) {
            window.requestAnimationFrame(() => focus.focus({ preventScroll: true }));
        }
    }

    function clearPlan() {
        dialog.runGeneration += 1;
        dialog.plan = null;
        clearLater(dialog.collapseTimer);
        clearLater(dialog.completionTimer);
        dialog.collapseTimer = null;
        dialog.completionTimer = null;
        clearInvocationLayer();
    }

    function collapseAndReset() {
        clearPlan();
        dialog.popoverOpen = false;
        setMode("collapsed", { panelOpen: false });
        window.requestAnimationFrame(() => nodes.launcher.focus({ preventScroll: true }));
    }

    function collapseKeepingRun() {
        if (!["planning", "running", "completed"].includes(dialog.mode)) {
            return;
        }
        if (dialog.mode === "planning") {
            collapseAndReset();
            return;
        }
        dialog.popoverOpen = false;
        setMode(dialog.mode, { panelOpen: false });
        window.requestAnimationFrame(() => nodes.launcher.focus({ preventScroll: true }));
    }

    function openComposer() {
        if (!dialog.enabled) return;
        clearPlan();
        dialog.popoverOpen = false;
        setMode("composing", { panelOpen: false, focus: nodes.composerInput });
    }

    function showPlanPanel() {
        if (!dialog.plan || !["running", "completed"].includes(dialog.mode)) {
            return;
        }
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = null;
        setMode(dialog.mode, { panelOpen: true, focus: nodes.planClose });
    }

    function scheduleRunningCollapse() {
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = later(() => {
            dialog.collapseTimer = null;
            if (dialog.mode === "running" && dialog.panelOpen
                && !dock.matches(":hover")
                && !dock.contains(document.activeElement)) {
                collapseKeepingRun();
            }
        }, AI_RUNNING_LEAVE_DELAY_MS);
    }

    function showProgressPopover() {
        if (!["running", "completed"].includes(dialog.mode) || dialog.panelOpen) {
            return;
        }
        clearLater(dialog.popoverTimer);
        dialog.popoverTimer = later(() => {
            dialog.popoverTimer = null;
            dialog.popoverOpen = true;
            renderProgress();
            updateSurfaces();
        }, 70);
    }

    function hideProgressPopover() {
        clearLater(dialog.popoverTimer);
        dialog.popoverTimer = later(() => {
            dialog.popoverTimer = null;
            dialog.popoverOpen = false;
            updateSurfaces();
        }, AI_PROGRESS_HIDE_DELAY_MS);
    }

    function submitGoal() {
        const goal = nodes.composerInput.value.trim();
        dialog.runGeneration += 1;
        clearInvocationLayer();
        dialog.plan = createAiDemoPlan(goal, getModules());
        setMode("planning", { panelOpen: true, focus: nodes.run });
    }

    function parseCssDuration(value, fallback) {
        const text = String(value ?? "").trim();
        if (text.endsWith("ms")) {
            const parsed = Number.parseFloat(text);
            return Number.isFinite(parsed) ? parsed : fallback;
        }
        if (text.endsWith("s")) {
            const parsed = Number.parseFloat(text);
            return Number.isFinite(parsed) ? parsed * 1000 : fallback;
        }
        return fallback;
    }

    function visibleRect(node) {
        if (!node) return null;
        const rect = node.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return null;
        if (rect.right <= 0 || rect.bottom <= 0
            || rect.left >= window.innerWidth || rect.top >= window.innerHeight) {
            return null;
        }
        return rect;
    }

    function findActionTarget(moduleId) {
        const id = String(moduleId ?? "").trim();
        if (!id || typeof globalThis.CSS?.escape !== "function") {
            return null;
        }
        const escaped = globalThis.CSS.escape(id);
        const selectors = [
            `#main-content [data-module-view="${escaped}"]`,
            `#main-content .module-card[data-module-id="${escaped}"]`,
            `#main-content [data-module-id="${escaped}"]`
        ];
        for (const selector of selectors) {
            const node = document.querySelector(selector);
            if (visibleRect(node)) {
                return node;
            }
        }
        const sidebarTarget = document.querySelector(
            `.nav-item--module[data-module-id="${escaped}"]`
        );
        return visibleRect(sidebarTarget) ? sidebarTarget : null;
    }

    function invocationOrigin() {
        const source = dialog.panelOpen
            ? visibleRect(nodes.plan)
            : visibleRect(nodes.launcher) ?? visibleRect(dock);
        if (!source) return null;
        return {
            x: source.left + Math.min(24, source.width / 3),
            y: source.bottom - Math.min(20, source.height / 3)
        };
    }

    async function animateInvocation(moduleId) {
        clearInvocationLayer();
        const origin = invocationOrigin();
        const target = findActionTarget(moduleId);
        if (!origin || !target) {
            await new Promise((resolve) => window.setTimeout(resolve, 80));
            return false;
        }

        const targetRect = target.getBoundingClientRect();
        const destination = {
            x: targetRect.left + targetRect.width / 2,
            y: targetRect.top + targetRect.height / 2
        };
        const dx = destination.x - origin.x;
        const dy = destination.y - origin.y;
        const distance = Math.hypot(dx, dy);
        const angle = Math.atan2(dy, dx);
        const route = document.createElement("span");
        route.className = "ai-call-route";
        route.style.left = `${origin.x}px`;
        route.style.top = `${origin.y}px`;
        route.style.width = `${distance}px`;
        route.style.transform = `rotate(${angle}rad)`;

        const dot = document.createElement("span");
        dot.className = "ai-call-dot";
        dot.style.left = `${origin.x}px`;
        dot.style.top = `${origin.y}px`;
        callLayer.append(route, dot);
        target.classList.add("is-ai-invocation-target");

        const duration = parseCssDuration(
            getComputedStyle(dock).getPropertyValue("--duration-ai-call"),
            620
        );
        let animation = null;
        if (duration > 0 && typeof dot.animate === "function") {
            animation = dot.animate(
                [
                    { transform: "translate3d(0, 0, 0)", opacity: 0.55 },
                    { transform: `translate3d(${dx}px, ${dy}px, 0)`, opacity: 1 }
                ],
                {
                    duration,
                    easing: "linear",
                    fill: "forwards"
                }
            );
            await animation.finished.catch(() => {});
        } else {
            dot.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
        }

        let cleaned = false;
        const cleanup = () => {
            if (cleaned) return;
            cleaned = true;
            animation?.cancel();
            target.classList.remove("is-ai-invocation-target");
            route.remove();
            dot.remove();
            invocationCleanups.delete(cleanup);
        };
        invocationCleanups.add(cleanup);
        later(cleanup, 220);
        return true;
    }

    async function runDemoPlan() {
        if (!dialog.plan || dialog.mode === "running") {
            return;
        }
        const generation = ++dialog.runGeneration;
        clearLater(dialog.completionTimer);
        dialog.completionTimer = null;
        dialog.plan.actions.forEach((action) => {
            action.status = "pending";
        });
        setMode("running", { panelOpen: true });

        for (const action of dialog.plan.actions) {
            if (generation !== dialog.runGeneration) return;
            action.status = "running";
            renderAll();
            await animateInvocation(action.moduleId);
            if (generation !== dialog.runGeneration) return;
            action.status = "completed";
            renderAll();
            await new Promise((resolve) => window.setTimeout(resolve, AI_STEP_GAP_MS));
        }

        if (generation !== dialog.runGeneration) return;
        dialog.mode = "completed";
        dialog.panelOpen = true;
        renderAll();
        onNotice("界面演示完成，未执行真实 AI 或模块动作。");
        clearLater(dialog.completionTimer);
        dialog.completionTimer = later(() => {
            dialog.completionTimer = null;
            if (dialog.mode === "completed") {
                collapseKeepingRun();
            }
        }, AI_COMPLETION_HOLD_MS);
    }

    function setEnabled(enabled) {
        dialog.enabled = enabled === true;
        if (!dialog.enabled) {
            clearPlan();
            dialog.popoverOpen = false;
            setMode("collapsed", { panelOpen: false });
        }
    }

    function reset() {
        clearPlan();
        dialog.popoverOpen = false;
        setMode("collapsed", { panelOpen: false });
    }

    function onLauncherClick() {
        if (dialog.mode === "running" || dialog.mode === "completed") {
            showPlanPanel();
            return;
        }
        openComposer();
    }

    function onPlanCloseClick() {
        collapseKeepingRun();
    }

    function onComposerSubmit(event) {
        event.preventDefault();
        submitGoal();
    }

    function onComposerKeyDown(event) {
        if (event.key === "Escape") {
            event.preventDefault();
            collapseAndReset();
            return;
        }
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            submitGoal();
        }
    }

    function onDockKeyDown(event) {
        if (event.key !== "Escape") return;
        if (dialog.mode === "collapsed") return;
        event.preventDefault();
        if (dialog.mode === "composing" || dialog.mode === "planning") {
            collapseAndReset();
        } else {
            collapseKeepingRun();
        }
    }

    nodes.launcher.addEventListener("click", onLauncherClick);
    nodes.launcher.addEventListener("pointerenter", showProgressPopover);
    nodes.launcher.addEventListener("pointerleave", hideProgressPopover);
    nodes.launcher.addEventListener("focus", showProgressPopover);
    nodes.launcher.addEventListener("blur", hideProgressPopover);
    nodes.composerClose.addEventListener("click", collapseAndReset);
    nodes.composerForm.addEventListener("submit", onComposerSubmit);
    nodes.composerInput.addEventListener("keydown", onComposerKeyDown);
    nodes.planClose.addEventListener("click", onPlanCloseClick);
    nodes.plan.addEventListener("pointerenter", () => {
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = null;
    });
    nodes.plan.addEventListener("pointerleave", () => {
        if (dialog.mode === "running") {
            scheduleRunningCollapse();
        }
    });
    nodes.plan.addEventListener("focusin", () => {
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = null;
    });
    nodes.plan.addEventListener("focusout", () => {
        if (dialog.mode === "running") {
            scheduleRunningCollapse();
        }
    });
    nodes.run.addEventListener("click", () => void runDemoPlan());
    dock.addEventListener("keydown", onDockKeyDown);

    const stop = () => {
        clearAllTimers();
        clearInvocationLayer();
    };
    window.addEventListener("pagehide", stop, { once: true });
    setMode("collapsed", { panelOpen: false });

    return {
        setEnabled,
        reset,
        openWithText(text = "") {
            if (!dialog.enabled) return;
            openComposer();
            nodes.composerInput.value = String(text ?? "");
            nodes.composerInput.setSelectionRange(
                nodes.composerInput.value.length,
                nodes.composerInput.value.length
            );
        },
        destroy() {
            dialog.runGeneration += 1;
            stop();
            window.removeEventListener("pagehide", stop);
        }
    };
}
