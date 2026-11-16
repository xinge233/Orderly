import { icon, normalizeAiIcon } from "./icons.js";

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
const AI_COMPLETION_HOLD_MS = 4000;
const AI_STEP_GAP_MS = 120;
const AI_DRAG_THRESHOLD_PX = 6;
const AI_SNAP_DISTANCE_PX = 76;
const AI_POSITION_STORAGE_KEY = "orderly.ai.dock.position.v1";
const AI_VIEWPORT_MARGIN_PX = 12;

export function resolveAiSurfaceLayout({
    dockLeft,
    dockTop,
    dockWidth = 58,
    dockHeight = 58,
    viewportWidth,
    viewportHeight,
    margin = AI_VIEWPORT_MARGIN_PX,
    gap = 12,
    composerWidth = 720,
    composerHeight = 68,
    planWidth = 288,
    planContentHeight = 420,
    composerOpen = true
}) {
    const safeWidth = Math.max(1, viewportWidth - (margin * 2));
    const dockRight = dockLeft + dockWidth;
    const dockBottom = dockTop + dockHeight;
    const spaceOnRight = Math.max(0, viewportWidth - margin - dockLeft);
    const spaceOnLeft = Math.max(0, dockRight - margin);
    const horizontal = spaceOnRight > spaceOnLeft ? "right" : "left";
    const availableWidth = Math.max(
        1,
        Math.min(safeWidth, horizontal === "right" ? spaceOnRight : spaceOnLeft)
    );
    const resolvedComposerWidth = Math.min(composerWidth, availableWidth);
    const resolvedPlanWidth = Math.min(planWidth, availableWidth);
    const roomAbove = Math.max(0, dockTop - margin);
    const roomBelow = Math.max(0, viewportHeight - margin - dockBottom);
    const surfaceY = roomAbove >= roomBelow ? "above" : "below";
    const composerTop = surfaceY === "above"
        ? dockBottom - composerHeight
        : dockTop;
    const composerBottom = composerTop + composerHeight;
    const composerBox = horizontal === "right"
        ? {
            left: dockLeft,
            right: dockLeft + resolvedComposerWidth,
            top: composerTop,
            bottom: composerBottom
        }
        : {
            left: dockRight - resolvedComposerWidth,
            right: dockRight,
            top: composerTop,
            bottom: composerBottom
        };
    const spaceAbove = Math.max(0, composerBox.top - margin - gap);
    const spaceBelow = Math.max(
        0,
        viewportHeight - margin - composerBox.bottom - gap
    );
    const vertical = spaceAbove >= spaceBelow ? "above" : "below";
    const maxPlanHeight = Math.max(
        0,
        Math.min(viewportHeight * 0.64, vertical === "above" ? spaceAbove : spaceBelow)
    );
    const visiblePlanHeight = Math.min(planContentHeight, maxPlanHeight);
    const planHorizontalBox = horizontal === "right"
        ? { left: dockLeft, right: dockLeft + resolvedPlanWidth }
        : { left: dockRight - resolvedPlanWidth, right: dockRight };
    const planTop = vertical === "above"
        ? composerBox.top - gap - visiblePlanHeight
        : composerBox.bottom + gap;
    const planBottom = planTop + visiblePlanHeight;

    return {
        horizontal,
        vertical,
        surfaceY,
        availableWidth,
        composerOpen,
        composerWidth: resolvedComposerWidth,
        composerHeight,
        planWidth: resolvedPlanWidth,
        maxPlanHeight,
        composerBox,
        planBox: {
            ...planHorizontalBox,
            top: planTop,
            bottom: planBottom
        }
    };
}

/**
 * 只有完全收起且没有形变/吸附动画时才允许移动 dock。
 *
 * @param {{mode?: string, composerOpen?: boolean, panelOpen?: boolean, morphing?: boolean, snapAnimating?: boolean}} state dock 状态
 * @returns {boolean} 是否允许拖动
 */
export function canDragAiDock({
    mode = "collapsed",
    composerOpen = false,
    panelOpen = false,
    morphing = false,
    snapAnimating = false
} = {}) {
    return mode === "collapsed"
        && composerOpen !== true
        && panelOpen !== true
        && morphing !== true
        && snapAnimating !== true;
}

/**
 * 计算 dock 左上角需要滑动到的默认吸附位置。
 *
 * @param {{dockWidth: number, dockHeight: number, snapCenter: {x: number, y: number}}} input 几何
 * @returns {{x: number, y: number}} 目标左上角
 */
export function resolveAiSnapPosition({ dockWidth, dockHeight, snapCenter }) {
    return {
        x: snapCenter.x - (dockWidth / 2),
        y: snapCenter.y - (dockHeight / 2)
    };
}

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
        composerSend: dock.querySelector("#ai-send-button"),
        sendIcon: dock.querySelector("#ai-send-icon"),
        plan: dock.querySelector("#ai-plan-panel"),
        planClose: dock.querySelector("#ai-plan-close"),
        planTitle: dock.querySelector("#ai-plan-title"),
        planList: dock.querySelector("#ai-plan-list"),
        risk: dock.querySelector("#ai-risk-indicator"),
        run: dock.querySelector("#ai-run-button"),
        progress: dock.querySelector("#ai-progress-popover"),
        progressTitle: dock.querySelector("#ai-progress-title"),
        progressList: dock.querySelector("#ai-progress-list"),
        snapTarget: document.querySelector("#ai-snap-target")
    };
    const missing = Object.entries(nodes).find(([, node]) => !node);
    if (missing) {
        throw new Error(`AI 对话框缺少节点：${missing[0]}`);
    }

    const timers = new Set();
    const invocationCleanups = new Set();
    const dialog = {
        enabled: true,
        mode: "collapsed",
        composerOpen: false,
        panelOpen: false,
        popoverOpen: false,
        plan: null,
        runGeneration: 0,
        popoverTimer: null,
        collapseTimer: null,
        completionTimer: null,
        morphAnimation: null,
        iconAnimation: null,
        contentAnimation: null,
        snapAnimation: null,
        drag: null,
        dragging: false,
        morphing: false,
        snapAnimating: false,
        aiIcon: "spark",
        suppressClickUntil: 0,
        position: null
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
        dialog.morphAnimation = null;
        dialog.iconAnimation = null;
        dialog.contentAnimation = null;
        dialog.snapAnimation = null;
    }

    function clearInvocationLayer() {
        for (const cleanup of invocationCleanups) {
            cleanup();
        }
        invocationCleanups.clear();
        callLayer.replaceChildren();
    }

    function clamp(value, minimum, maximum) {
        return Math.min(Math.max(value, minimum), Math.max(minimum, maximum));
    }

    function cssPixels(value, fallback) {
        const parsed = Number.parseFloat(value);
        return Number.isFinite(parsed) ? parsed : fallback;
    }

    function readStoredPosition() {
        try {
            const raw = window.localStorage.getItem(AI_POSITION_STORAGE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!Number.isFinite(parsed?.x) || !Number.isFinite(parsed?.y)) {
                return null;
            }
            return { x: parsed.x, y: parsed.y };
        } catch {
            return null;
        }
    }

    function storePosition(position) {
        try {
            window.localStorage.setItem(
                AI_POSITION_STORAGE_KEY,
                JSON.stringify(position)
            );
        } catch {
            // 本地存储不可用时仍允许本次会话拖动。
        }
    }

    function clearStoredPosition() {
        try {
            window.localStorage.removeItem(AI_POSITION_STORAGE_KEY);
        } catch {
            // 本地存储不可用不影响吸附回默认位置。
        }
    }

    function updateExpandedGeometry() {
        const rect = dock.getBoundingClientRect();
        if (!rect.width || !rect.height) {
            return;
        }
        const free = dock.dataset.anchor === "free";
        const style = getComputedStyle(dock);
        const viewportWidth = window.innerWidth;
        const viewportHeight = window.innerHeight;
        const baseComposerWidth = cssPixels(
            style.getPropertyValue(free ? "--ai-composer-free-width" : "--ai-composer-width"),
            free ? 720 : 860
        );
        const basePlanWidth = cssPixels(
            style.getPropertyValue("--ai-plan-width"),
            288
        );
        const composerHeight = nodes.composerForm.offsetHeight
            || cssPixels(style.getPropertyValue("--ai-composer-height"), 68);
        const gap = cssPixels(style.getPropertyValue("--space-3"), 12);
        const layout = resolveAiSurfaceLayout({
            dockLeft: rect.left,
            dockTop: rect.top,
            dockWidth: rect.width,
            dockHeight: rect.height,
            viewportWidth,
            viewportHeight,
            margin: AI_VIEWPORT_MARGIN_PX,
            gap,
            composerWidth: baseComposerWidth,
            composerHeight,
            planWidth: basePlanWidth,
            planContentHeight: nodes.plan.scrollHeight || 420,
            composerOpen: dialog.composerOpen
        });
        dock.dataset.surfaceX = layout.horizontal;
        dock.dataset.surfaceY = layout.vertical;
        dock.dataset.expandY = layout.surfaceY;
        dock.style.setProperty(
            "--ai-composer-current-width",
            `${layout.composerWidth}px`
        );
        nodes.plan.style.width = `${layout.planWidth}px`;
        nodes.plan.style.maxHeight = `${layout.maxPlanHeight}px`;
        nodes.progress.style.width = `${Math.min(280, layout.availableWidth)}px`;

        const horizontalAxis = layout.horizontal === "right"
            ? { left: "0px", right: "auto" }
            : { left: "auto", right: "0px" };
        const verticalAxis = layout.surfaceY === "above"
            ? { top: "auto", bottom: "0px" }
            : { top: "0px", bottom: "auto" };
        const placeSurface = (node, offset) => {
            node.style.left = horizontalAxis.left;
            node.style.right = horizontalAxis.right;
            if (layout.vertical === "above") {
                node.style.top = "auto";
                node.style.bottom = `${offset}px`;
            } else {
                node.style.top = `${offset}px`;
                node.style.bottom = "auto";
            }
            node.style.transformOrigin = `${
                layout.horizontal === "right" ? "left" : "right"
            } ${layout.vertical === "above" ? "bottom" : "top"}`;
        };
        nodes.launcher.style.left = horizontalAxis.left;
        nodes.launcher.style.right = horizontalAxis.right;
        nodes.launcher.style.top = verticalAxis.top;
        nodes.launcher.style.bottom = verticalAxis.bottom;
        nodes.composer.style.left = horizontalAxis.left;
        nodes.composer.style.right = horizontalAxis.right;
        nodes.composer.style.top = verticalAxis.top;
        nodes.composer.style.bottom = verticalAxis.bottom;
        nodes.composer.style.transformOrigin = `${
            layout.horizontal === "right" ? "left" : "right"
        } ${layout.surfaceY === "above" ? "bottom" : "top"}`;
        const composerTopOffset = layout.composerBox.top - rect.top;
        const composerBottomOffset = layout.composerBox.bottom - rect.top;
        const planOffset = layout.vertical === "above"
            ? rect.height - composerTopOffset + gap
            : composerBottomOffset + gap;
        placeSurface(nodes.plan, planOffset);
        placeSurface(nodes.progress, planOffset);
    }

    function setFreePosition(x, y, { persist = false } = {}) {
        const rect = dock.getBoundingClientRect();
        const width = rect.width || 58;
        const height = rect.height || 58;
        const position = {
            x: clamp(x, AI_VIEWPORT_MARGIN_PX, window.innerWidth - width - AI_VIEWPORT_MARGIN_PX),
            y: clamp(y, AI_VIEWPORT_MARGIN_PX, window.innerHeight - height - AI_VIEWPORT_MARGIN_PX)
        };
        dock.dataset.anchor = "free";
        dock.style.setProperty("--ai-dock-left", `${position.x}px`);
        dock.style.setProperty("--ai-dock-top", `${position.y}px`);
        dialog.position = position;
        if (persist) {
            storePosition(position);
        }
        updateExpandedGeometry();
    }

    function resetToDefaultAnchor() {
        dock.dataset.anchor = "default";
        dock.style.removeProperty("--ai-dock-left");
        dock.style.removeProperty("--ai-dock-top");
        dialog.position = null;
        clearStoredPosition();
        updateExpandedGeometry();
    }

    function restoreStoredPosition() {
        const position = readStoredPosition();
        if (position) {
            setFreePosition(position.x, position.y);
        } else {
            resetToDefaultAnchor();
        }
    }

    function defaultSnapCenter() {
        const rect = nodes.snapTarget.getBoundingClientRect();
        return {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2
        };
    }

    function defaultSnapDistance() {
        const rect = dock.getBoundingClientRect();
        const center = defaultSnapCenter();
        const dockCenter = {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2
        };
        return Math.hypot(dockCenter.x - center.x, dockCenter.y - center.y);
    }

    function isNearDefaultSnap() {
        return defaultSnapDistance() <= AI_SNAP_DISTANCE_PX;
    }

    function setSnapTargetState(visible, near = false) {
        nodes.snapTarget.dataset.visible = String(visible);
        nodes.snapTarget.dataset.near = String(visible && near);
    }

    function animateSnapToDefault() {
        if (dialog.snapAnimating
            || !canDragAiDock({
                mode: dialog.mode,
                composerOpen: dialog.composerOpen,
                panelOpen: dialog.panelOpen,
                morphing: dialog.morphing
            })) {
            return;
        }
        const rect = dock.getBoundingClientRect();
        const target = resolveAiSnapPosition({
            dockWidth: rect.width,
            dockHeight: rect.height,
            snapCenter: defaultSnapCenter()
        });
        const dx = target.x - rect.left;
        const dy = target.y - rect.top;
        const style = getComputedStyle(dock);
        const duration = parseCssDuration(
            style.getPropertyValue("--duration-normal"),
            240
        );
        const easing = style.getPropertyValue("--ease-out").trim()
            || "cubic-bezier(0.22, 1, 0.36, 1)";
        dialog.snapAnimating = true;
        dock.dataset.snapAnimating = "true";
        dock.dataset.draggable = "false";
        setSnapTargetState(true, true);
        dialog.suppressClickUntil = Date.now() + duration + 250;

        const finish = () => {
            dialog.snapAnimation = null;
            dialog.snapAnimating = false;
            dock.dataset.snapAnimating = "false";
            dock.dataset.draggable = String(canDragAiDock(dialog));
            setSnapTargetState(false, false);
        };
        if (duration <= 0 || typeof dock.animate !== "function") {
            resetToDefaultAnchor();
            finish();
            return;
        }

        const animation = dock.animate(
            [
                { transform: "translate3d(0, 0, 0)" },
                { transform: `translate3d(${dx}px, ${dy}px, 0)` }
            ],
            { duration, easing, fill: "forwards" }
        );
        dialog.snapAnimation = animation;
        void animation.finished.then(() => {
            if (dialog.snapAnimation !== animation) return;
            resetToDefaultAnchor();
            animation.cancel();
            finish();
        }).catch(() => {});
    }

    function renderAiIcon() {
        const inSendButton = nodes.sendIcon.contains(nodes.launcherIcon);
        nodes.launcherIcon.dataset.aiIcon = dialog.aiIcon;
        nodes.launcherIcon.innerHTML = icon(dialog.aiIcon, inSendButton ? 16 : 26);
    }

    function updateSurfaces() {
        const composerOpen = dialog.composerOpen;
        const planOpen = dialog.panelOpen
            && ["planning", "running", "completed"].includes(dialog.mode);
        const expanded = composerOpen || planOpen;
        const running = dialog.mode === "running";
        const completed = dialog.mode === "completed";
        const busy = ["planning", "running", "completed"].includes(dialog.mode);

        dock.dataset.mode = dialog.mode;
        dock.dataset.composer = composerOpen ? "open" : "closed";
        dock.dataset.panel = planOpen ? "open" : "closed";
        dock.dataset.popover = dialog.popoverOpen ? "open" : "closed";
        dock.dataset.dragging = dialog.dragging ? "true" : "false";
        dock.dataset.expanded = expanded ? "true" : "false";
        dock.dataset.morphing = dialog.morphing ? "true" : "false";
        dock.dataset.snapAnimating = dialog.snapAnimating ? "true" : "false";
        dock.dataset.draggable = String(canDragAiDock(dialog));
        dock.dataset.demo = "true";

        nodes.composer.setAttribute("aria-hidden", String(!composerOpen));
        nodes.composer.inert = !composerOpen;
        nodes.plan.setAttribute("aria-hidden", String(!planOpen));
        nodes.plan.inert = !planOpen;
        nodes.launcher.setAttribute("aria-hidden", String(expanded));
        nodes.launcher.inert = expanded;
        nodes.launcher.tabIndex = expanded ? -1 : 0;
        nodes.launcher.setAttribute("aria-expanded", String(expanded));
        nodes.composerInput.disabled = busy;
        nodes.composerSend.disabled = busy;
        nodes.composerInput.setAttribute("aria-busy", String(busy));
        nodes.progress.setAttribute("aria-hidden", String(!dialog.popoverOpen));

        nodes.launcher.dataset.status = running
            ? "running"
            : completed
                ? "completed"
                : "idle";
        renderAiIcon();
        nodes.launcher.setAttribute(
            "aria-label",
            running
                ? "AI 界面演示正在运行，可拖动调整位置"
                : completed
                    ? "AI 界面演示已完成，点击开始新对话"
                    : "打开 AI 对话；可拖动调整位置"
        );
        nodes.planClose.setAttribute(
            "aria-label",
            running ? "收起运行中的计划" : "关闭计划"
        );
        nodes.run.disabled = dialog.mode === "running" || !dialog.plan;
        nodes.run.innerHTML = `${icon("check-plain", 15)}<span>执行</span>`;
        updateExpandedGeometry();
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

    function animateDockTransition({
        expanded,
        beforeRect,
        beforeStyle,
        beforeIconRect
    }) {
        dialog.morphAnimation?.cancel();
        dialog.iconAnimation?.cancel();
        dialog.contentAnimation?.cancel();
        dialog.morphAnimation = null;
        dialog.iconAnimation = null;
        dialog.contentAnimation = null;
        const iconInSendButton = nodes.sendIcon.contains(nodes.launcherIcon);
        if (expanded && !iconInSendButton) {
            nodes.sendIcon.append(nodes.launcherIcon);
        } else if (!expanded && iconInSendButton) {
            nodes.launcher.append(nodes.launcherIcon);
        }
        renderAiIcon();

        const style = getComputedStyle(dock);
        const duration = parseCssDuration(
            style.getPropertyValue("--duration-normal"),
            240
        );
        if (duration <= 0
            || typeof nodes.launcher.animate !== "function"
            || typeof nodes.launcherIcon.animate !== "function") {
            dialog.morphing = false;
            dock.dataset.morphing = "false";
            dock.dataset.draggable = String(canDragAiDock(dialog));
            delete dock.dataset.morph;
            return;
        }

        const afterRect = nodes.launcher.getBoundingClientRect();
        const afterStyle = getComputedStyle(nodes.launcher);
        const afterIconRect = nodes.launcherIcon.getBoundingClientRect();
        if (!beforeRect.width || !afterRect.width
            || !beforeIconRect.width || !afterIconRect.width) {
            dialog.morphing = false;
            dock.dataset.morphing = "false";
            delete dock.dataset.morph;
            return;
        }
        const easing = style.getPropertyValue("--ease-out").trim()
            || "cubic-bezier(0.22, 1, 0.36, 1)";
        const iconDx = (beforeIconRect.left + (beforeIconRect.width / 2))
            - (afterIconRect.left + (afterIconRect.width / 2));
        const iconDy = (beforeIconRect.top + (beforeIconRect.height / 2))
            - (afterIconRect.top + (afterIconRect.height / 2));
        const iconScale = beforeIconRect.width / afterIconRect.width;

        dialog.morphing = true;
        dock.dataset.morphing = "true";
        dock.dataset.draggable = "false";
        dock.dataset.morph = expanded ? "opening" : "closing";
        const morphAnimation = nodes.launcher.animate(
            [
                {
                    width: `${beforeRect.width}px`,
                    height: `${beforeRect.height}px`,
                    borderColor: beforeStyle.borderColor,
                    borderWidth: beforeStyle.borderWidth,
                    borderRadius: beforeStyle.borderRadius,
                    backgroundColor: beforeStyle.backgroundColor,
                    color: beforeStyle.color
                },
                {
                    width: `${afterRect.width}px`,
                    height: `${afterRect.height}px`,
                    borderColor: afterStyle.borderColor,
                    borderWidth: afterStyle.borderWidth,
                    borderRadius: afterStyle.borderRadius,
                    backgroundColor: afterStyle.backgroundColor,
                    color: afterStyle.color
                }
            ],
            { duration, easing, fill: "none" }
        );
        const iconAnimation = nodes.launcherIcon.animate(
            [
                {
                    transform: `translate3d(${iconDx}px, ${iconDy}px, 0) scale(${iconScale})`
                },
                { transform: "translate3d(0, 0, 0) scale(1)" }
            ],
            { duration, easing, fill: "none" }
        );
        const contentAnimation = nodes.composer.animate(
            expanded
                ? [
                    { opacity: 0, offset: 0 },
                    { opacity: 0, offset: 0.42 },
                    { opacity: 1, offset: 1 }
                ]
                : [
                    { opacity: 1, offset: 0 },
                    { opacity: 0.12, offset: 0.58 },
                    { opacity: 0, offset: 1 }
                ],
            { duration, easing, fill: "forwards" }
        );
        dialog.morphAnimation = morphAnimation;
        dialog.iconAnimation = iconAnimation;
        dialog.contentAnimation = contentAnimation;
        void Promise.allSettled([
            morphAnimation.finished,
            iconAnimation.finished,
            contentAnimation.finished
        ])
            .then(() => {
                if (dialog.morphAnimation !== morphAnimation) return;
                dialog.morphAnimation = null;
                dialog.iconAnimation = null;
                dialog.contentAnimation = null;
                dialog.morphing = false;
                dock.dataset.morphing = "false";
                dock.dataset.draggable = String(canDragAiDock(dialog));
                delete dock.dataset.morph;
            });
    }

    function setState({
        mode = dialog.mode,
        composerOpen = dialog.composerOpen,
        panelOpen = dialog.panelOpen,
        focus = null
    } = {}) {
        const wasComposerOpen = dialog.composerOpen;
        const beforeRect = nodes.launcher.getBoundingClientRect();
        const beforeStyle = getComputedStyle(nodes.launcher);
        const beforeIconRect = nodes.launcherIcon.getBoundingClientRect();
        dialog.mode = mode;
        dialog.composerOpen = composerOpen;
        dialog.panelOpen = panelOpen;
        if (!["running", "completed"].includes(mode)) {
            dialog.popoverOpen = false;
        }
        renderAll();
        if (wasComposerOpen !== composerOpen) {
            animateDockTransition({
                expanded: composerOpen,
                beforeRect,
                beforeStyle,
                beforeIconRect
            });
        }
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

    function collapseAndReset({ focusLauncher = true } = {}) {
        clearPlan();
        dialog.popoverOpen = false;
        nodes.composerInput.value = "";
        setState({
            mode: "collapsed",
            composerOpen: false,
            panelOpen: false
        });
        if (focusLauncher) {
            window.requestAnimationFrame(() => nodes.launcher.focus({ preventScroll: true }));
        }
    }

    function collapsePlanOnly({ focusLauncher = false } = {}) {
        if (!dialog.panelOpen) {
            return;
        }
        dialog.popoverOpen = false;
        setState({
            mode: dialog.mode,
            composerOpen: dialog.composerOpen,
            panelOpen: false
        });
        if (focusLauncher) {
            window.requestAnimationFrame(() => nodes.launcher.focus({ preventScroll: true }));
        }
    }

    function openComposer() {
        if (!dialog.enabled) return;
        clearPlan();
        dialog.popoverOpen = false;
        setState({
            mode: "composing",
            composerOpen: true,
            panelOpen: false,
            focus: nodes.composerInput
        });
    }

    function showPlanPanel() {
        if (!dialog.plan || !["planning", "running", "completed"].includes(dialog.mode)) {
            return;
        }
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = null;
        setState({
            mode: dialog.mode,
            composerOpen: true,
            panelOpen: true,
            focus: nodes.planClose
        });
    }

    function scheduleRunningCollapse() {
        clearLater(dialog.collapseTimer);
        dialog.collapseTimer = later(() => {
            dialog.collapseTimer = null;
            if (dialog.mode === "running" && dialog.panelOpen
                && !dock.matches(":hover")
                && !dock.contains(document.activeElement)) {
                collapsePlanOnly({ focusLauncher: true });
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
        setState({
            mode: "planning",
            composerOpen: true,
            panelOpen: true,
            focus: nodes.run
        });
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
        const angle = Math.atan2(dy, dx);

        const dot = document.createElement("span");
        dot.className = "ai-call-dot";
        dot.style.left = `${origin.x}px`;
        dot.style.top = `${origin.y}px`;
        callLayer.append(dot);
        target.classList.add("is-ai-invocation-target");

        const duration = parseCssDuration(
            getComputedStyle(dock).getPropertyValue("--duration-ai-call"),
            280
        );
        let animation = null;
        if (duration > 0 && typeof dot.animate === "function") {
            animation = dot.animate(
                [
                    {
                        transform: `translate3d(0, 0, 0) rotate(${angle}rad) scaleX(1)`,
                        opacity: 0.55,
                        offset: 0
                    },
                    {
                        transform: `translate3d(${dx * 0.24}px, ${dy * 0.24}px, 0) rotate(${angle}rad) scaleX(1.45)`,
                        opacity: 0.72,
                        offset: 0.5
                    },
                    {
                        transform: `translate3d(${dx * 0.7}px, ${dy * 0.7}px, 0) rotate(${angle}rad) scaleX(1.9)`,
                        opacity: 0.9,
                        offset: 0.8
                    },
                    {
                        transform: `translate3d(${dx}px, ${dy}px, 0) rotate(${angle}rad) scaleX(2.15)`,
                        opacity: 1,
                        offset: 1
                    }
                ],
                {
                    duration,
                    easing: "cubic-bezier(0.32, 0, 0.95, 0.2)",
                    fill: "forwards"
                }
            );
            await animation.finished.catch(() => {});
        } else {
            dot.style.transform = `translate3d(${dx}px, ${dy}px, 0) rotate(${angle}rad) scaleX(2.15)`;
        }

        let cleaned = false;
        const cleanup = () => {
            if (cleaned) return;
            cleaned = true;
            animation?.cancel();
            target.classList.remove("is-ai-invocation-target");
            dot.remove();
            invocationCleanups.delete(cleanup);
        };
        invocationCleanups.add(cleanup);
        later(cleanup, 160);
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
        setState({
            mode: "running",
            composerOpen: true,
            panelOpen: true
        });

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
        dialog.composerOpen = true;
        dialog.panelOpen = true;
        renderAll();
        onNotice("界面演示完成，未执行真实 AI 或模块动作。");
        clearLater(dialog.completionTimer);
        dialog.completionTimer = later(() => {
            dialog.completionTimer = null;
            if (dialog.mode === "completed") {
                collapseAndReset({ focusLauncher: false });
            }
        }, AI_COMPLETION_HOLD_MS);
    }

    function setEnabled(enabled) {
        dialog.enabled = enabled === true;
        if (!dialog.enabled) {
            clearPlan();
            dialog.popoverOpen = false;
            setState({
                mode: "collapsed",
                composerOpen: false,
                panelOpen: false
            });
        }
    }

    function reset() {
        clearPlan();
        dialog.popoverOpen = false;
        setState({
            mode: "collapsed",
            composerOpen: false,
            panelOpen: false
        });
    }

    function onLauncherClick() {
        if (Date.now() < dialog.suppressClickUntil
            || dialog.dragging
            || dialog.morphing
            || dialog.snapAnimating) {
            return;
        }
        if (dialog.mode === "completed") {
            collapseAndReset({ focusLauncher: false });
            openComposer();
            return;
        }
        if (dialog.mode === "running" || dialog.mode === "planning") {
            showPlanPanel();
            return;
        }
        if (dialog.composerOpen) {
            nodes.composerInput.focus({ preventScroll: true });
            return;
        }
        openComposer();
    }

    function onPlanCloseClick() {
        collapsePlanOnly({ focusLauncher: true });
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
        collapseAndReset();
    }

    function onLauncherPointerDown(event) {
        if (event.button !== 0
            || !dialog.enabled
            || !canDragAiDock(dialog)) {
            return;
        }
        const rect = dock.getBoundingClientRect();
        dialog.drag = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            originX: rect.left,
            originY: rect.top,
            started: false
        };
        try {
            nodes.launcher.setPointerCapture?.(event.pointerId);
        } catch {
            // 合成事件或已结束的指针仍可通过页面坐标完成拖动。
        }
    }

    function onLauncherPointerMove(event) {
        const drag = dialog.drag;
        if (!drag || drag.pointerId !== event.pointerId) return;
        const dx = event.clientX - drag.startX;
        const dy = event.clientY - drag.startY;
        if (!drag.started
            && Math.hypot(dx, dy) < AI_DRAG_THRESHOLD_PX) {
            return;
        }
        if (!drag.started) {
            if (!canDragAiDock(dialog)) {
                dialog.drag = null;
                return;
            }
            drag.started = true;
            dialog.dragging = true;
            setSnapTargetState(true, false);
            setFreePosition(drag.originX, drag.originY);
        }
        event.preventDefault();
        setFreePosition(drag.originX + dx, drag.originY + dy);
        setSnapTargetState(true, isNearDefaultSnap());
    }

    function finishLauncherDrag(event) {
        const drag = dialog.drag;
        if (!drag || drag.pointerId !== event.pointerId) return;
        const shouldSnap = drag.started && isNearDefaultSnap();
        dialog.dragging = false;
        dialog.drag = null;
        dock.dataset.dragging = "false";
        if (nodes.launcher.hasPointerCapture?.(event.pointerId)) {
            try {
                nodes.launcher.releasePointerCapture(event.pointerId);
            } catch {
                // 指针捕获可能已由浏览器自动释放。
            }
        }
        if (!drag.started) {
            setSnapTargetState(false, false);
            return;
        }
        if (shouldSnap) {
            animateSnapToDefault();
            return;
        }
        if (dialog.position) {
            setFreePosition(
                dialog.position.x,
                dialog.position.y,
                { persist: true }
            );
        }
        dialog.suppressClickUntil = Date.now() + 250;
        setSnapTargetState(false, false);
    }

    function onLauncherKeyDown(event) {
        if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) {
            return;
        }
        if (!canDragAiDock(dialog)) {
            return;
        }
        event.preventDefault();
        const previousDistance = defaultSnapDistance();
        const rect = dock.getBoundingClientRect();
        const step = event.shiftKey ? 4 : 16;
        const offset = {
            ArrowLeft: [-step, 0],
            ArrowRight: [step, 0],
            ArrowUp: [0, -step],
            ArrowDown: [0, step]
        }[event.key];
        setFreePosition(rect.left + offset[0], rect.top + offset[1], { persist: true });
        const nextDistance = defaultSnapDistance();
        if (nextDistance <= AI_SNAP_DISTANCE_PX && nextDistance < previousDistance) {
            animateSnapToDefault();
        }
    }

    function onDocumentPointerDown(event) {
        if (!dialog.panelOpen || dock.contains(event.target)) {
            return;
        }
        collapsePlanOnly();
    }

    function onWindowResize() {
        if (dialog.snapAnimating) {
            return;
        }
        if (!dialog.position) {
            updateExpandedGeometry();
            return;
        }
        setFreePosition(dialog.position.x, dialog.position.y, { persist: true });
    }

    nodes.launcher.addEventListener("click", onLauncherClick);
    nodes.launcher.addEventListener("pointerdown", onLauncherPointerDown);
    nodes.launcher.addEventListener("pointermove", onLauncherPointerMove);
    nodes.launcher.addEventListener("pointerup", finishLauncherDrag);
    nodes.launcher.addEventListener("pointercancel", finishLauncherDrag);
    nodes.launcher.addEventListener("keydown", onLauncherKeyDown);
    nodes.launcher.addEventListener("pointerenter", showProgressPopover);
    nodes.launcher.addEventListener("pointerleave", hideProgressPopover);
    nodes.launcher.addEventListener("focus", showProgressPopover);
    nodes.launcher.addEventListener("blur", hideProgressPopover);
    nodes.composer.addEventListener("pointerenter", showProgressPopover);
    nodes.composer.addEventListener("pointerleave", hideProgressPopover);
    nodes.composer.addEventListener("focusin", showProgressPopover);
    nodes.composer.addEventListener("focusout", hideProgressPopover);
    nodes.progress.addEventListener("pointerenter", () => {
        clearLater(dialog.popoverTimer);
        dialog.popoverTimer = null;
    });
    nodes.progress.addEventListener("pointerleave", hideProgressPopover);
    nodes.progress.addEventListener("focusin", () => {
        clearLater(dialog.popoverTimer);
        dialog.popoverTimer = null;
    });
    nodes.progress.addEventListener("focusout", hideProgressPopover);
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
    document.addEventListener("pointerdown", onDocumentPointerDown);
    window.addEventListener("resize", onWindowResize);

    const stop = () => {
        dialog.morphAnimation?.cancel();
        dialog.iconAnimation?.cancel();
        dialog.contentAnimation?.cancel();
        dialog.snapAnimation?.cancel();
        dialog.morphing = false;
        dialog.snapAnimating = false;
        dialog.morphAnimation = null;
        dialog.iconAnimation = null;
        dialog.contentAnimation = null;
        dialog.snapAnimation = null;
        clearAllTimers();
        clearInvocationLayer();
    };
    window.addEventListener("pagehide", stop, { once: true });
    restoreStoredPosition();
    setState({
        mode: "collapsed",
        composerOpen: false,
        panelOpen: false
    });

    return {
        setEnabled,
        reset,
        setIcon(name) {
            dialog.aiIcon = normalizeAiIcon(name);
            renderAiIcon();
        },
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
            window.removeEventListener("resize", onWindowResize);
            document.removeEventListener("pointerdown", onDocumentPointerDown);
        }
    };
}
