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
const AI_DRAG_THRESHOLD_PX = 6;
const AI_SNAP_DISTANCE_PX = 76;
const AI_POSITION_STORAGE_KEY = "orderly.ai.dock.position.v1";
const AI_VIEWPORT_MARGIN_PX = 12;

function normalizeSettings(value) {
    return {
        configured: value?.configured === true,
        mode: value?.mode === "CUSTOM" ? "CUSTOM" : "DEEPSEEK",
        allowDeleteDirect: value?.allowDeleteDirect === true,
        baseUrl: typeof value?.baseUrl === "string" ? value.baseUrl : "",
        model: typeof value?.model === "string" ? value.model : ""
    };
}

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
 * 规范化执行状态。
 *
 * @param {unknown} value 原始状态
 * @returns {string} 小写状态
 */
export function normalizeExecutionStatus(value) {
    return String(value ?? "").trim().toLowerCase();
}

/**
 * 判断执行是否已经成功完成。
 *
 * @param {object | null} execution 执行记录
 * @returns {boolean} 是否成功
 */
export function executionSucceeded(execution) {
    if (!["completed", "success", "succeeded"].includes(
        normalizeExecutionStatus(execution?.status)
    )) {
        return false;
    }
    const steps = Array.isArray(execution?.steps) ? execution.steps : [];
    return !steps.some((step) =>
        [
            "failed",
            "failed_partially_rolled_back",
            "failed_rolled_back",
            "partially_rolled_back",
            "rejected",
            "rollback_failed",
            "rolled_back"
        ].includes(
            normalizeExecutionStatus(step?.status)
        )
    );
}

/**
 * 从执行记录提取真实失败原因。
 *
 * @param {object | null} execution 执行记录
 * @returns {string} 可展示原因
 */
export function executionFailureMessage(execution) {
    const steps = Array.isArray(execution?.steps) ? execution.steps : [];
    const failed = steps.find((step) =>
        [
            "failed",
            "failed_partially_rolled_back",
            "failed_rolled_back",
            "partially_rolled_back",
            "rejected",
            "rollback_failed"
        ].includes(normalizeExecutionStatus(step?.status))
    );
    if (failed?.message) {
        return String(failed.message);
    }
    const labels = {
        failed: "执行失败",
        failed_partially_rolled_back: "执行失败，部分动作未能回滚",
        failed_rolled_back: "执行失败，已完成自动回滚",
        partial: "部分动作未完成",
        partially_rolled_back: "执行仅部分回滚",
        rejected: "执行被拒绝",
        rollback_failed: "执行失败且回滚失败",
        rolled_back: "执行已回滚"
    };
    return labels[normalizeExecutionStatus(execution?.status)]
        ?? "宿主未返回成功执行记录";
}

/**
 * 获取需要重新读取数据的已完成模块。
 *
 * @param {object | null} execution 执行记录
 * @returns {string[]} 模块 id
 */
export function executionCompletedModules(execution) {
    const status = normalizeExecutionStatus(execution?.status);
    if (!["completed", "partial", "success", "succeeded"].includes(status)) {
        return [];
    }
    const steps = Array.isArray(execution?.steps) ? execution.steps : [];
    if (["completed", "success", "succeeded"].includes(status)
        && !executionSucceeded(execution)) {
        return [];
    }
    return [...new Set(steps
        .filter((step) => normalizeExecutionStatus(step?.status) === "completed")
        .map((step) => String(step?.moduleId ?? "").trim())
        .filter(Boolean))];
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
    getAiSettings = () => ({}),
    saveAiSettings = async () => {
        throw new Error("AI 设置接口未配置。");
    },
    clearAiSettings = async () => ({}),
    testAiSettings = async () => ({ ok: true, message: "连接成功" }),
    interpretGoal = async () => {
        throw new Error("AI 自然语言接口未配置。");
    },
    confirmAiPlan = async () => {
        throw new Error("AI 计划确认接口未配置。");
    },
    onAiSettingsChanged = () => {},
    onExecutionFinished = () => {},
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
        inlineKey: dock.querySelector("#ai-inline-api-key"),
        composerInput: dock.querySelector("#ai-composer-input"),
        composerClose: dock.querySelector("#ai-composer-close"),
        composerSend: dock.querySelector("#ai-send-button"),
        sendIcon: dock.querySelector("#ai-send-icon"),
        plan: dock.querySelector("#ai-plan-panel"),
        planEyebrow: dock.querySelector(".ai-plan-panel__eyebrow"),
        planClose: dock.querySelector("#ai-plan-close"),
        planTitle: dock.querySelector("#ai-plan-title"),
        planList: dock.querySelector("#ai-plan-list"),
        risk: dock.querySelector("#ai-risk-indicator"),
        run: dock.querySelector("#ai-run-button"),
        progress: dock.querySelector("#ai-progress-popover"),
        progressTitle: dock.querySelector("#ai-progress-title"),
        progressList: dock.querySelector("#ai-progress-list"),
        snapTarget: document.querySelector("#ai-snap-target"),
        demoBadgeFull: dock.querySelector(".ai-demo-badge__full"),
        demoBadgeCompact: dock.querySelector(".ai-demo-badge__compact")
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
        question: null,
        execution: null,
        conversationId: "",
        settings: normalizeSettings(getAiSettings()),
        requestGeneration: 0,
        busy: false,
        error: "",
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

    function notifyExecutionFinished(execution) {
        void Promise.resolve()
            .then(() => onExecutionFinished(execution))
            .catch(() => {});
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
            && ["planning", "running", "completed", "failed", "question", "error"].includes(dialog.mode);
        const expanded = composerOpen || planOpen;
        const running = dialog.mode === "running";
        const completed = dialog.mode === "completed";
        const failed = dialog.mode === "failed";
        const busy = dialog.busy === true;
        const configured = dialog.settings.configured === true;

        dock.dataset.mode = dialog.mode;
        dock.dataset.composer = composerOpen ? "open" : "closed";
        dock.dataset.panel = planOpen ? "open" : "closed";
        dock.dataset.popover = dialog.popoverOpen ? "open" : "closed";
        dock.dataset.dragging = dialog.dragging ? "true" : "false";
        dock.dataset.expanded = expanded ? "true" : "false";
        dock.dataset.morphing = dialog.morphing ? "true" : "false";
        dock.dataset.snapAnimating = dialog.snapAnimating ? "true" : "false";
        dock.dataset.draggable = String(canDragAiDock(dialog));
        dock.dataset.demo = configured ? "false" : "true";
        dock.dataset.aiConfigured = String(configured);

        nodes.composer.setAttribute("aria-hidden", String(!composerOpen));
        nodes.composer.inert = !composerOpen;
        nodes.plan.setAttribute("aria-hidden", String(!planOpen));
        nodes.plan.inert = !planOpen;
        nodes.launcher.setAttribute("aria-hidden", String(expanded));
        nodes.launcher.inert = expanded;
        nodes.launcher.tabIndex = expanded ? -1 : 0;
        nodes.launcher.setAttribute("aria-expanded", String(expanded));
        nodes.composerInput.disabled = busy || !configured;
        nodes.inlineKey.disabled = busy || configured;
        nodes.composerSend.disabled = busy;
        nodes.composerInput.setAttribute("aria-busy", String(busy));
        nodes.inlineKey.setAttribute("aria-busy", String(busy));
        nodes.progress.setAttribute("aria-hidden", String(!dialog.popoverOpen));
        nodes.inlineKey.hidden = configured;
        nodes.composerInput.hidden = !configured;
        if (nodes.demoBadgeFull) {
            nodes.demoBadgeFull.textContent = configured ? "AI 已接入" : "输入 API Key";
        }
        if (nodes.demoBadgeCompact) {
            nodes.demoBadgeCompact.textContent = configured ? "已接入" : "接入 AI";
        }
        if (nodes.composerSend.lastElementChild) {
            nodes.composerSend.lastElementChild.textContent = configured ? "生成" : "接入";
        }

        nodes.launcher.dataset.status = running
            ? "running"
            : completed
                ? "completed"
                : failed
                    ? "failed"
                    : "idle";
        renderAiIcon();
        nodes.launcher.setAttribute(
            "aria-label",
            running
                ? "AI 正在执行，可拖动调整位置"
                : completed
                    ? "AI 执行已完成，点击开始新对话"
                    : failed
                        ? "AI 执行未成功，点击查看结果"
                        : "打开 AI 对话；可拖动调整位置"
        );
        nodes.planClose.setAttribute(
            "aria-label",
            running ? "收起运行中的计划" : "关闭计划"
        );
        nodes.run.disabled = dialog.mode === "running" || !dialog.plan;
        nodes.run.innerHTML = `${icon("check-plain", 15)}<span>确认执行</span>`;
        updateExpandedGeometry();
    }

    function riskLabel(risk) {
        return AI_RISK_LABEL[risk] ?? AI_RISK_LABEL.READ_ONLY;
    }

    function statusLabel(status) {
        const normalized = normalizeExecutionStatus(status);
        const labels = {
            completed: "已完成",
            failed: "失败",
            failed_partially_rolled_back: "失败，部分回滚",
            failed_rolled_back: "失败并已回滚",
            partial: "部分完成",
            partially_rolled_back: "部分回滚",
            rejected: "已拒绝",
            rollback_failed: "回滚失败",
            rolled_back: "已回滚",
            running: "运行中",
            success: "已完成",
            succeeded: "已完成",
            waiting: "等待"
        };
        return labels[normalized] ?? "等待";
    }

    function renderExecutionResult() {
        const execution = dialog.execution;
        const steps = Array.isArray(execution?.steps) ? execution.steps : [];
        const succeeded = executionSucceeded(execution);
        const completed = steps.filter((step) =>
            normalizeExecutionStatus(step?.status) === "completed"
        ).length;
        nodes.planEyebrow.textContent = succeeded ? "AI 执行结果" : "AI 执行失败";
        nodes.planTitle.textContent = succeeded
            ? `已执行 ${completed}/${steps.length} 个动作`
            : executionFailureMessage(execution);
        nodes.planList.replaceChildren();

        let highestRisk = "READ_ONLY";
        steps.forEach((action, index) => {
            const status = normalizeExecutionStatus(action?.status) || "waiting";
            const risk = action?.risk || "READ_ONLY";
            highestRisk = higherAiRisk(highestRisk, risk);

            const item = document.createElement("li");
            item.className = "ai-plan-step";
            item.dataset.status = status;

            const indexNode = document.createElement("span");
            indexNode.className = "ai-plan-step__index";
            indexNode.textContent = String(index + 1).padStart(2, "0");

            const copy = document.createElement("span");
            copy.className = "ai-plan-step__copy";
            const title = document.createElement("strong");
            title.textContent = action?.title || action?.actionId || "未命名动作";
            const meta = document.createElement("small");
            meta.textContent = `${action?.moduleId || "未知模块"} · ${riskLabel(risk)}`;
            copy.append(title, meta);
            if (action?.message) {
                const message = document.createElement("small");
                message.className = "ai-plan-step__message";
                message.textContent = String(action.message);
                copy.append(message);
            }

            const state = document.createElement("span");
            state.className = "ai-plan-step__status";
            state.dataset.status = status;
            state.textContent = statusLabel(status);

            const riskNode = document.createElement("span");
            riskNode.className = "ai-risk-chip";
            riskNode.dataset.risk = risk;
            riskNode.textContent = riskLabel(risk);

            item.append(indexNode, copy, state, riskNode);
            nodes.planList.append(item);
        });

        nodes.risk.dataset.risk = highestRisk;
        nodes.risk.innerHTML = succeeded
            ? `${icon("check-plain", 14)}<span>执行完成</span>`
            : `${icon("alert", 14)}<span>执行未成功</span>`;
        nodes.run.disabled = true;
        updateSurfaces();
    }

    function renderPlan() {
        if (dialog.error && !dialog.plan && !dialog.question) {
            nodes.planEyebrow.textContent = "AI 暂不可用";
            nodes.planTitle.textContent = dialog.error;
            nodes.planList.replaceChildren();
            nodes.risk.dataset.risk = "READ_ONLY";
            nodes.risk.innerHTML = `${icon("alert", 14)}<span>未执行任何动作</span>`;
            nodes.run.disabled = true;
            updateSurfaces();
            return;
        }
        if (dialog.question) {
            nodes.planEyebrow.textContent = "AI 需要澄清";
            nodes.planTitle.textContent = dialog.question.prompt || "请补充信息";
            nodes.planList.replaceChildren();
            const options = Array.isArray(dialog.question.options)
                ? dialog.question.options
                : [];
            options.forEach((option) => {
                const item = document.createElement("li");
                item.className = "ai-plan-step ai-plan-step--question";
                const button = document.createElement("button");
                button.type = "button";
                button.className = "text-button";
                button.textContent = option;
                button.addEventListener("click", () => {
                    nodes.composerInput.value = option;
                    submitGoal();
                });
                item.append(button);
                nodes.planList.append(item);
            });
            nodes.risk.dataset.risk = "READ_ONLY";
            nodes.risk.innerHTML = `${icon("shield", 14)}<span>尚未执行</span>`;
            nodes.run.disabled = true;
            updateSurfaces();
            return;
        }
        if (dialog.execution) {
            renderExecutionResult();
            return;
        }
        if (!dialog.plan) {
            nodes.planEyebrow.textContent = dialog.settings.configured
                ? "AI 工作流"
                : "AI 未接入";
            nodes.planTitle.textContent = dialog.settings.configured
                ? "尚未生成计划"
                : "请先输入 API Key";
            nodes.planList.replaceChildren();
            nodes.risk.dataset.risk = "READ_ONLY";
            nodes.risk.innerHTML = `${icon("shield", 14)}<span>风险等级：只读</span>`;
            nodes.run.disabled = true;
            updateSurfaces();
            return;
        }

        nodes.planEyebrow.textContent = "AI 计划 · 请确认";
        nodes.planTitle.textContent = dialog.plan.goal || "AI 计划";
        nodes.planList.replaceChildren();
        const steps = Array.isArray(dialog.plan.steps) ? dialog.plan.steps : [];
        steps.forEach((action, index) => {
            const item = document.createElement("li");
            item.className = "ai-plan-step";
            item.dataset.status = action.status || "waiting";

            const indexNode = document.createElement("span");
            indexNode.className = "ai-plan-step__index";
            indexNode.textContent = String(index + 1).padStart(2, "0");

            const copy = document.createElement("span");
            copy.className = "ai-plan-step__copy";
            const title = document.createElement("strong");
            title.textContent = action.title || action.actionId;
            const meta = document.createElement("small");
            meta.textContent = `${action.moduleId || "未知模块"} · ${riskLabel(action.risk)}`;
            copy.append(title, meta);

            const status = document.createElement("span");
            status.className = "ai-plan-step__status";
            status.dataset.status = action.status || "waiting";
            status.textContent = statusLabel(action.status);

            const risk = document.createElement("span");
            risk.className = "ai-risk-chip";
            risk.dataset.risk = action.risk;
            risk.textContent = riskLabel(action.risk);

            item.append(indexNode, copy, status, risk);
            nodes.planList.append(item);
        });

        const highestRisk = dialog.plan.highestRisk || "READ_ONLY";
        nodes.risk.dataset.risk = highestRisk;
        nodes.risk.innerHTML = `${icon("alert", 14)}<span>风险等级：${riskLabel(highestRisk)}</span>`;
        nodes.run.disabled = dialog.busy || !dialog.plan
            || (dialog.plan.status && dialog.plan.status !== "PREVIEW");
        updateSurfaces();
    }

    function renderProgress() {
        const executionSteps = Array.isArray(dialog.execution?.steps)
            ? dialog.execution.steps
            : [];
        if (executionSteps.length === 0 && !dialog.plan) {
            nodes.progressTitle.textContent = dialog.settings.configured
                ? "AI 执行进度"
                : "AI 未接入";
            nodes.progressList.replaceChildren();
            return;
        }
        const steps = executionSteps.length
            ? executionSteps.map((step) => ({
                title: step.title || step.actionId,
                moduleTitle: step.moduleId,
                status: normalizeExecutionStatus(step.status) || "waiting",
                message: step.message ? String(step.message) : ""
            }))
            : (dialog.plan.steps || []).map((step) => ({
                title: step.title || step.actionId,
                moduleTitle: step.moduleId,
                status: normalizeExecutionStatus(step.status) || "waiting",
                message: step.message ? String(step.message) : ""
            }));
        const completed = steps.filter((step) => step.status === "completed").length;
        const succeeded = executionSucceeded(dialog.execution);
        nodes.progressTitle.textContent = dialog.mode === "completed"
            ? `执行完成 · ${completed}/${steps.length}`
            : dialog.mode === "failed"
                ? `执行未成功 · ${completed}/${steps.length}`
                : `运行进度 · ${completed}/${steps.length}`;
        nodes.progressList.replaceChildren();
        steps.forEach((action) => {
            const item = document.createElement("li");
            item.dataset.status = action.status;
            const copy = document.createElement("span");
            copy.className = "ai-progress-copy";
            const title = document.createElement("strong");
            title.textContent = action.title;
            const meta = document.createElement("small");
            meta.textContent = `${action.moduleTitle || "目标模块不可见"} · ${statusLabel(action.status)}`;
            copy.append(title, meta);
            if (action.message && !succeeded) {
                const detail = document.createElement("small");
                detail.className = "ai-progress-detail";
                detail.textContent = action.message;
                copy.append(detail);
            }
            const state = document.createElement("span");
            state.className = "ai-progress-state";
            if (action.status === "completed") {
                state.innerHTML = icon("check-plain", 15);
                state.setAttribute("aria-label", "已完成");
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
        dialog.requestGeneration += 1;
        dialog.plan = null;
        dialog.question = null;
        dialog.execution = null;
        dialog.error = "";
        dialog.busy = false;
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
        dialog.conversationId = "";
        dialog.popoverOpen = false;
        const configured = dialog.settings.configured === true;
        setState({
            mode: configured ? "composing" : "unconfigured",
            composerOpen: true,
            panelOpen: false,
            focus: configured ? nodes.composerInput : nodes.inlineKey
        });
    }

    function showPlanPanel() {
        if ((!dialog.plan && !dialog.question && !dialog.error)
            || !["planning", "running", "completed", "question", "error"].includes(dialog.mode)) {
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

    async function connectTemporaryApi() {
        const apiKey = nodes.inlineKey.value.trim();
        if (!apiKey) {
            onNotice("请输入 API Key。", { error: true });
            nodes.inlineKey.focus({ preventScroll: true });
            return;
        }
        dialog.busy = true;
        setState({
            mode: "unconfigured",
            composerOpen: true,
            panelOpen: false
        });
        try {
            const saved = await saveAiSettings({
                mode: "DEEPSEEK",
                apiKey,
                baseUrl: null,
                model: null,
                allowDeleteDirect: dialog.settings.allowDeleteDirect
            });
            const test = await testAiSettings();
            if (test?.ok === false) {
                throw new Error(test.message || "模型连接测试失败");
            }
            dialog.settings = normalizeSettings(saved);
            onAiSettingsChanged(saved);
            nodes.inlineKey.value = "";
            dialog.busy = false;
            setState({
                mode: "composing",
                composerOpen: true,
                panelOpen: false,
                focus: nodes.composerInput
            });
            onNotice("DeepSeek 已临时接入；API Key 只保存在当前进程。");
        } catch (error) {
            try {
                const cleared = await clearAiSettings();
                dialog.settings = normalizeSettings(cleared);
                onAiSettingsChanged(cleared);
            } catch {
                // 保留原始连接错误，清除失败不覆盖用户可见原因。
            }
            dialog.busy = false;
            dialog.error = error?.message || "AI 接入失败";
            setState({
                mode: "unconfigured",
                composerOpen: true,
                panelOpen: false
            });
            onNotice(dialog.error, { error: true });
        }
    }

    async function submitGoal() {
        if (!dialog.settings.configured) {
            await connectTemporaryApi();
            return;
        }
        const goal = nodes.composerInput.value.trim();
        if (!goal) {
            onNotice("请输入自然语言指令。", { error: true });
            nodes.composerInput.focus({ preventScroll: true });
            return;
        }
        const generation = ++dialog.requestGeneration;
        dialog.busy = true;
        dialog.error = "";
        clearInvocationLayer();
        setState({
            mode: "sending",
            composerOpen: true,
            panelOpen: false
        });
        try {
            const response = await interpretGoal({
                goal,
                conversationId: dialog.conversationId || null,
                clientRequestId: globalThis.crypto?.randomUUID?.()
                    ?? `ai-${Date.now()}-${Math.random().toString(36).slice(2)}`
            });
            if (generation !== dialog.requestGeneration) return;
            dialog.conversationId = response?.conversationId || dialog.conversationId;
            dialog.busy = false;
            if (response?.kind === "QUESTION") {
                dialog.plan = null;
                dialog.execution = null;
                dialog.question = response.question || {
                    prompt: response.message || "请补充信息。",
                    options: []
                };
                setState({
                    mode: "question",
                    composerOpen: true,
                    panelOpen: true,
                    focus: nodes.planClose
                });
                onNotice(response.message || "AI 需要补充信息。");
                return;
            }
            if (response?.kind === "PLAN") {
                dialog.question = null;
                dialog.execution = null;
                dialog.plan = response.plan;
                setState({
                    mode: "planning",
                    composerOpen: true,
                    panelOpen: true,
                    focus: nodes.run
                });
                onNotice(response.message || "已生成 AI 计划，请确认。");
                return;
            }
            if (response?.kind === "ACTIONS") {
                const execution = response.execution || null;
                const succeeded = executionSucceeded(execution);
                dialog.question = null;
                dialog.plan = null;
                dialog.execution = execution;
                dialog.error = "";
                setState({
                    mode: succeeded ? "completed" : "failed",
                    composerOpen: true,
                    panelOpen: true,
                    focus: nodes.planClose
                });
                onNotice(
                    succeeded
                        ? response.message || "动作已执行。"
                        : `执行失败：${executionFailureMessage(execution)}`,
                    { error: !succeeded }
                );
                notifyExecutionFinished(execution);
                return;
            }
            throw new Error("AI 返回了未知结果类型。");
        } catch (error) {
            if (generation !== dialog.requestGeneration) return;
            dialog.busy = false;
            dialog.error = error?.message || "AI 请求失败";
            dialog.plan = null;
            dialog.question = null;
            setState({
                mode: "error",
                composerOpen: true,
                panelOpen: true,
                focus: nodes.planClose
            });
            onNotice(dialog.error, { error: true });
        }
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

    async function confirmPlan() {
        if (!dialog.plan?.planId || dialog.busy) {
            return;
        }
        const generation = ++dialog.requestGeneration;
        dialog.busy = true;
        setState({
            mode: "running",
            composerOpen: true,
            panelOpen: true
        });
        try {
            const steps = Array.isArray(dialog.plan.steps) ? dialog.plan.steps : [];
            for (const step of steps) {
                if (generation !== dialog.requestGeneration) return;
                await animateInvocation(step.moduleId);
            }
            const record = await confirmAiPlan(dialog.plan.planId);
            if (generation !== dialog.requestGeneration) return;
            dialog.execution = record;
            const succeeded = executionSucceeded(record);
            dialog.busy = false;
            dialog.plan = {
                ...dialog.plan,
                status: record?.status || "COMPLETED"
            };
            setState({
                mode: succeeded ? "completed" : "failed",
                composerOpen: true,
                panelOpen: true,
                focus: nodes.planClose
            });
            onNotice(
                succeeded
                    ? "AI 计划执行完成。"
                    : `AI 计划执行失败：${executionFailureMessage(record)}`,
                { error: !succeeded }
            );
            notifyExecutionFinished(record);
        } catch (error) {
            if (generation !== dialog.requestGeneration) return;
            dialog.busy = false;
            dialog.error = error?.message || "AI 计划执行失败";
            setState({
                mode: "error",
                composerOpen: true,
                panelOpen: true,
                focus: nodes.planClose
            });
            onNotice(dialog.error, { error: true });
        }
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
        if (["running", "planning", "failed", "question", "error"].includes(dialog.mode)) {
            showPlanPanel();
            return;
        }
        if (dialog.composerOpen) {
            const target = dialog.settings.configured
                ? nodes.composerInput
                : nodes.inlineKey;
            target.focus({ preventScroll: true });
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
    nodes.run.addEventListener("click", () => void confirmPlan());
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
        setAiSettings(settings) {
            dialog.settings = normalizeSettings(settings);
            if (!dialog.settings.configured && dialog.mode !== "collapsed") {
                setState({
                    mode: "unconfigured",
                    composerOpen: true,
                    panelOpen: false
                });
                return;
            }
            updateSurfaces();
        },
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
