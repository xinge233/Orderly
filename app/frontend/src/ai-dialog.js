/**
 * 框架级独立 AI Dock。
 *
 * 职责：管理 AI 对话入口、独立 AI 计划、澄清、只读 Query 结果、执行进度与确认交互。
 * 它不使用旧版计划审查状态，也不直接持有宿主 API；模型请求、计划确认、设置与执行
 * 都通过 app.js 注入的回调完成。
 *
 * 状态来源：AI 设置来自宿主快照，回合结果来自 interpretGoal，计划执行结果来自
 * confirmAiPlan；拖动位置是唯一进入 localStorage 的局部界面偏好。API Key 仅存在于
 * 输入控件和 Python 服务进程内，不进入本模块状态或浏览器存储。
 *
 * 边界：Java 宿主仍是契约校验、授权、执行、快照、审计和回滚的唯一控制面；本组件
 * 只负责界面状态、交互动画和结果呈现，失败时不得伪造计划或执行成功。
 */
import { icon, normalizeAiIcon } from "./icons.js";
import {
    buildQueryPayloadView,
    EVENT_ORIGIN,
    formatQueryValue,
    normalizeAiQueryResponse,
    normalizeQueryView,
    resolveQueryPath,
    summarizeQueryPayload
} from "./models.js";

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
const AI_INVOCATION_TYPES = new Set(["STEP", "QUERY"]);
const AI_INVOCATION_ORIGINS = new Set([
    EVENT_ORIGIN.AI_DIRECT,
    EVENT_ORIGIN.AI_QUERY,
    EVENT_ORIGIN.AI_PLAN
]);

function normalizeSettings(value) {
    return {
        configured: value?.configured === true,
        mode: value?.mode === "CUSTOM" ? "CUSTOM" : "DEEPSEEK",
        allowDeleteDirect: value?.allowDeleteDirect === true,
        allowSendQueryResultsToModel: value?.allowSendQueryResultsToModel === true,
        baseUrl: typeof value?.baseUrl === "string" ? value.baseUrl : "",
        model: typeof value?.model === "string" ? value.model : ""
    };
}

/**
 * 从当前 AI 回合的宿主事件中解析需要提示的模块。
 *
 * 历史 SSE 重放、终态事件和非 AI 来源都不会通过校验；时间戳缺失时也保守忽略，
 * 避免把重连快照误当成正在进行的新调用。
 *
 * @param {object} event 已归一化事件
 * @param {number} startedAt 当前 AI 回合开始时间
 * @returns {string} 模块 id；不应提示时为空字符串
 */
export function invocationEventModuleId(event, startedAt = 0) {
    const started = Number(startedAt);
    if (!Number.isFinite(started) || started <= 0) {
        return "";
    }
    const type = String(event?.type ?? "").trim().toUpperCase();
    const status = String(event?.status ?? "").trim().toUpperCase();
    const origin = String(event?.origin ?? "").trim().toUpperCase();
    const moduleId = String(event?.moduleId ?? "").trim();
    const timeValue = Number(event?.timeValue);
    if (!AI_INVOCATION_TYPES.has(type)
        || status !== "RUNNING"
        || !AI_INVOCATION_ORIGINS.has(origin)
        || !moduleId
        || !Number.isFinite(timeValue)
        || timeValue < started) {
        return "";
    }
    return moduleId;
}

/**
 * 计算收起态 Dock 周围可摆放的输入区与计划面板几何。
 *
 * 该函数只做纯布局决策：先根据左右可用空间选择伸展方向，再根据上下空间选择面板
 * 次序，并返回视口内夹紧后的矩形与可用宽度/高度。调用方负责把结果写入 CSS 变量。
 *
 * 副作用：无。
 *
 * @param {object} input 几何与尺寸
 * @param {number} input.dockLeft Dock 视口左坐标
 * @param {number} input.dockTop Dock 视口上坐标
 * @param {number} [input.dockWidth=58] Dock 宽度
 * @param {number} [input.dockHeight=58] Dock 高度
 * @param {number} input.viewportWidth 视口宽度
 * @param {number} input.viewportHeight 视口高度
 * @param {number} [input.margin=12] 视口安全边距
 * @param {number} [input.gap=12] 面板间距
 * @param {number} [input.composerWidth=720] 首选输入区宽度
 * @param {number} [input.composerHeight=68] 输入区高度
 * @param {number} [input.planWidth=288] 首选计划宽度
 * @param {number} [input.planContentHeight=420] 计划内容自然高度
 * @param {boolean} [input.composerOpen=true] 输入区是否打开
 * @returns {{
 *     horizontal: "left" | "right",
 *     vertical: "above" | "below",
 *     surfaceY: "above" | "below",
 *     availableWidth: number,
 *     composerOpen: boolean,
 *     composerWidth: number,
 *     composerHeight: number,
 *     planWidth: number,
 *     maxPlanHeight: number,
 *     composerBox: {left: number, right: number, top: number, bottom: number},
 *     planBox: {left: number, right: number, top: number, bottom: number}
 * }} 视口内布局
 */
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 副作用：无。
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
 * 挂载独立 AI Dock，并返回只暴露宿主外壳所需动作的控制器。
 *
 * 组件内部使用单一 dialog 状态机派生输入区、计划、查询、进度和形变状态；所有模型
 * 与会话能力由注入回调提供，组件不构造宿主 API、不执行 Action，也不在失败时回退到
 * 本地规则。请求/运行代次用于丢弃过期异步结果，timer 与 invocationCleanups 负责
 * 统一关闭延迟任务和调用轨迹。
 *
 * 副作用：查询并绑定 dock 内 DOM、注册 document/window 监听器、恢复 localStorage
 * 中的 Dock 位置、创建动画/定时器，并在页面隐藏或 destroy 时清理长生命周期资源。
 *
 * @param {object} options 挂载配置
 * @param {HTMLElement} options.dock AI Dock 根节点
 * @param {HTMLElement} options.callLayer 非交互调用轨迹覆盖层
 * @param {() => Array<object>} options.getModules 当前可见模块提供器
 * @param {() => object} [options.getAiSettings] AI 设置快照提供器
 * @param {(settings: object) => Promise<object>} [options.saveAiSettings] 保存进程内 AI 设置
 * @param {() => Promise<object>} [options.clearAiSettings] 清除进程内临时凭据
 * @param {() => Promise<{ok?: boolean, message?: string}>} [options.testAiSettings] 测试模型连接
 * @param {(request: object) => Promise<object>} [options.interpretGoal] 提交带 clientRequestId 的 AI 回合
 * @param {(planId: string) => Promise<object>} [options.confirmAiPlan] 请求宿主确认并执行独立 AI 计划
 * @param {(request: object) => Promise<boolean>} [options.beforeAiTurn] 在请求模型前等待模块构建完成
 * @param {(settings: object) => void} [options.onAiSettingsChanged] 回传已归一化设置
 * @param {(execution: object | null) => void} [options.onExecutionFinished] 通知框架刷新受影响模块
 * @param {(message: string, options?: {error?: boolean}) => void} [options.onNotice] 显示框架级提示
 * @returns {{
 *     setEnabled: (enabled: boolean) => void,
 *     reset: () => void,
 *     setAiSettings: (settings: object) => void,
 *     setIcon: (name: string) => void,
 *     openWithText: (text?: string) => void,
 *     destroy: () => void
 * }} Dock 控制器
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
    beforeAiTurn = async () => true,
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
    // mode 是唯一语义状态；composerOpen/panelOpen/popoverOpen 只描述可见面，updateSurfaces
    // 再派生 ARIA、inert 和禁用态。两个 generation 分别隔离回合响应与执行回调。
    const dialog = {
        enabled: true,
        mode: "collapsed",
        composerOpen: false,
        panelOpen: false,
        popoverOpen: false,
        plan: null,
        question: null,
        query: null,
        execution: null,
        conversationId: "",
        settings: normalizeSettings(getAiSettings()),
        requestGeneration: 0,
        busy: false,
        error: "",
        runGeneration: 0,
        invocationStartedAt: 0,
        invocationGeneration: 0,
        invocationSeen: new Set(),
        invocationChain: Promise.resolve(),
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

    // 可取消的延迟任务都登记在同一集合，页面隐藏或 destroy 时可一次清理，避免关闭
    // 动画、进度气泡和调用轨迹在组件卸载后继续操作 DOM。
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

    function resetInvocationTracking(startedAt = 0) {
        dialog.invocationGeneration += 1;
        dialog.invocationStartedAt = Number.isFinite(startedAt) ? startedAt : 0;
        dialog.invocationSeen.clear();
        dialog.invocationChain = Promise.resolve();
        clearInvocationLayer();
    }

    function beginInvocationTracking() {
        resetInvocationTracking(Date.now());
    }

    function enqueueInvocation(moduleId) {
        const generation = dialog.invocationGeneration;
        const task = dialog.invocationChain.then(() => {
            if (generation !== dialog.invocationGeneration) {
                return false;
            }
            return animateInvocation(moduleId);
        });
        dialog.invocationChain = task.catch(() => false);
    }

    function handleInvocationEvent(event) {
        const moduleId = invocationEventModuleId(event, dialog.invocationStartedAt);
        if (!moduleId) {
            return false;
        }
        const key = event?.sequence != null
            ? `seq:${event.sequence}`
            : [
                event?.type,
                event?.origin,
                event?.planId,
                event?.stepId,
                event?.moduleId,
                event?.status,
                event?.timeValue
            ].map((value) => String(value ?? "")).join(":");
        if (dialog.invocationSeen.has(key)) {
            return false;
        }
        dialog.invocationSeen.add(key);
        enqueueInvocation(moduleId);
        return true;
    }

    // 执行完成后的模块刷新属于辅助行为；刷新异常不能把宿主已返回的执行结果改成失败。
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

    // 只持久化非敏感的位置坐标；localStorage 被禁用或配额不足时继续支持当前会话内拖动。
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

    // 根据 Dock 真实位置和 CSS 语义尺寸计算四个方向的可用空间。结果通过 dataset 与
    // CSS 变量交给主题样式消费，确保展开面不越出视口、不覆盖 Dock 主体。
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

    // 自由位置在写入前夹紧到视口；persist=false 用于拖动帧，结束或键盘操作才落盘。
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

    // 吸附动画只改变视觉位移，结束时统一回到默认锚点并清除测量残留；无 Web Animations
    // 或动效时长为 0 时直接落位，保证关闭动效后几何仍完整可用。
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

    // 所有界面可见性都从状态派生，避免按钮、ARIA 与 CSS dataset 各自维护一份真值。
    // inert 加上 aria-hidden 同时移除键盘焦点并防止屏幕阅读器读到隐藏面板。
    function updateSurfaces() {
        const composerOpen = dialog.composerOpen;
        const planOpen = dialog.panelOpen
            && [
                "planning",
                "running",
                "completed",
                "failed",
                "question",
                "error",
                "query"
            ].includes(dialog.mode);
        const expanded = composerOpen || planOpen;
        const running = dialog.mode === "running";
        const completed = dialog.mode === "completed";
        const failed = dialog.mode === "failed";
        const query = dialog.mode === "query" ? dialog.query : null;
        const queryFailed = query?.status === "FAILED";
        const queryFinished = Boolean(query) && !queryFailed;
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
            : completed || queryFinished
                ? "completed"
                : failed || queryFailed
                    ? "failed"
                    : "idle";
        renderAiIcon();
        nodes.launcher.setAttribute(
            "aria-label",
            running
                ? "AI 正在执行，可拖动调整位置"
                : completed
                    ? "AI 执行已完成，点击开始新对话"
                    : query
                        ? `AI 本地查询${queryFailed ? "未全部完成" : "结果可用"}，点击查看`
                    : failed
                        ? "AI 执行未成功，点击查看结果"
                        : "打开 AI 对话；可拖动调整位置"
        );
        nodes.planClose.setAttribute(
            "aria-label",
            running
                ? "收起运行中的计划"
                : query
                    ? "关闭查询结果"
                    : "关闭计划"
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
            ok: "成功",
            running: "运行中",
            success: "已完成",
            succeeded: "已完成",
            truncated: "已截断",
            waiting: "等待"
        };
        return labels[normalized] ?? "等待";
    }

    // 执行结果直接消费宿主 steps；失败原因和成功判定使用同一套 helper，避免模型文案
    // 与真实执行状态不一致时把失败展示成完成。
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

    function elementText(tagName, className, text) {
        const node = document.createElement(tagName);
        if (className) {
            node.className = className;
        }
        node.textContent = String(text ?? "");
        return node;
    }

    function findQueryDisplay(item, index) {
        if (dialog.query?.view?.sourceQuery === index + 1) {
            return dialog.query.view;
        }
        const module = (getModules() || []).find(
            (candidate) => candidate?.moduleId === item.moduleId
        );
        const query = Array.isArray(module?.queries)
            ? module.queries.find((candidate) => candidate?.id === item.queryId)
            : null;
        const display = query?.display;
        if (!display) {
            return null;
        }
        return normalizeQueryView({
            sourceQuery: index + 1,
            layout: display.fallbackLayout,
            title: query.title || item.queryId,
            rowPath: display.rowPath,
            fields: display.fields,
            emptyText: display.emptyText
        }, item.payload);
    }

    function appendQueryPayloadView(container, node) {
        if (!node || node.type === "empty") {
            container.append(elementText("span", "ai-query-empty", node?.text || "无数据"));
            return;
        }
        if (node.type === "scalar") {
            container.append(elementText("span", "ai-query-scalar", node.value));
            return;
        }
        if (node.type === "array") {
            if (!node.count) {
                container.append(elementText("span", "ai-query-empty", "暂无数据"));
                return;
            }
            const list = document.createElement("ul");
            list.className = "ai-query-value-list";
            node.items.forEach((item) => {
                const row = document.createElement("li");
                appendQueryPayloadView(row, item);
                list.append(row);
            });
            container.append(list);
            if (node.truncated) {
                container.append(elementText(
                    "small",
                    "ai-query-more",
                    `另有 ${node.count - node.items.length} 项未展开`
                ));
            }
            return;
        }
        const list = document.createElement("dl");
        list.className = "ai-query-key-values";
        node.entries.forEach((entry) => {
            const term = document.createElement("dt");
            term.textContent = entry.key;
            const description = document.createElement("dd");
            appendQueryPayloadView(description, entry.value);
            list.append(term, description);
        });
        container.append(list);
        if (node.truncated) {
            container.append(elementText(
                "small",
                "ai-query-more",
                `另有 ${node.count - node.entries.length} 个字段未展开`
            ));
        }
    }

    function appendQueryFieldValue(container, value, format) {
        container.append(elementText(
            "span",
            "ai-query-field-value",
            formatQueryValue(value, format)
        ));
    }

    function appendStructuredQueryView(container, payload, view) {
        const layout = view?.layout || "keyValue";
        const rowNode = view?.rowPath
            ? resolveQueryPath(payload, view.rowPath)
            : payload;
        const rows = Array.isArray(rowNode) ? rowNode : [];
        const fieldSource = Array.isArray(rowNode) ? rowNode[0] : rowNode;
        const fields = Array.isArray(view?.fields) ? view.fields : [];
        if (["table", "list"].includes(layout) && view?.rowPath) {
            if (!rows.length) {
                container.append(elementText(
                    "span",
                    "ai-query-empty",
                    view.emptyText || "暂无数据"
                ));
                return;
            }
            if (layout === "table") {
                const wrapper = document.createElement("div");
                wrapper.className = "ai-query-table-wrap";
                const table = document.createElement("table");
                table.className = "ai-query-table";
                const head = document.createElement("thead");
                const headRow = document.createElement("tr");
                fields.forEach((field) => {
                    const cell = document.createElement("th");
                    cell.scope = "col";
                    cell.textContent = field.label;
                    headRow.append(cell);
                });
                head.append(headRow);
                const body = document.createElement("tbody");
                rows.slice(0, 50).forEach((row) => {
                    const rowNodeElement = document.createElement("tr");
                    fields.forEach((field) => {
                        const cell = document.createElement("td");
                        appendQueryFieldValue(
                            cell,
                            resolveQueryPath(row, field.key),
                            field.format
                        );
                        rowNodeElement.append(cell);
                    });
                    body.append(rowNodeElement);
                });
                table.append(head, body);
                wrapper.append(table);
                container.append(wrapper);
            } else {
                const list = document.createElement("ul");
                list.className = "ai-query-record-list";
                rows.slice(0, 50).forEach((row) => {
                    const item = document.createElement("li");
                    item.className = "ai-query-record";
                    fields.forEach((field) => {
                        const line = document.createElement("span");
                        line.className = "ai-query-record__line";
                        line.append(
                            elementText("small", "", field.label),
                            elementText(
                                "strong",
                                "",
                                formatQueryValue(
                                    resolveQueryPath(row, field.key),
                                    field.format
                                )
                            )
                        );
                        item.append(line);
                    });
                    list.append(item);
                });
                container.append(list);
            }
            if (rows.length > 50) {
                container.append(elementText(
                    "small",
                    "ai-query-more",
                    `另有 ${rows.length - 50} 项未展开`
                ));
            }
            return;
        }
        if (layout === "text") {
            const text = typeof payload === "string"
                ? payload
                : summarizeQueryPayload(payload, 1000);
            container.append(elementText("p", "ai-query-text", text));
            return;
        }
        if (fields.length && fieldSource && typeof fieldSource === "object") {
            const list = document.createElement("dl");
            list.className = "ai-query-key-values";
            fields.forEach((field) => {
                const term = document.createElement("dt");
                term.textContent = field.label;
                const description = document.createElement("dd");
                appendQueryFieldValue(
                    description,
                    resolveQueryPath(fieldSource, field.key),
                    field.format
                );
                list.append(term, description);
            });
            container.append(list);
            return;
        }
        container.append(elementText(
            "span",
            "ai-query-empty",
            view?.emptyText || "暂无数据"
        ));
    }

    // Query 只展示宿主返回的本地只读结果；失败或截断项不会补造成成功数据。
    function renderQueryResult() {
        const query = dialog.query;
        const queries = Array.isArray(query?.queries) ? query.queries : [];
        const completed = queries.filter((item) => item.status === "OK").length;
        const statusText = query?.status === "OK"
            ? "已完成"
            : query?.status === "TRUNCATED"
                ? "结果已截断"
                : "未全部完成";
        nodes.planEyebrow.textContent = `AI 本地查询 · ${statusText}`;
        const fallback = query?.status === "OK"
            ? `已完成 ${completed}/${queries.length} 条本地查询`
            : `查询可用结果 ${completed}/${queries.length}`;
        nodes.planTitle.textContent = query?.view?.title
            || [query?.message, query?.notice].filter(Boolean).join(" ")
            || fallback;
        nodes.planList.replaceChildren();

        queries.forEach((item, index) => {
            const visualStatus = item.status === "OK"
                ? "completed"
                : item.status === "TRUNCATED"
                    ? "truncated"
                    : "failed";
            const row = document.createElement("li");
            row.className = "ai-plan-step";
            row.dataset.status = visualStatus;

            const indexNode = document.createElement("span");
            indexNode.className = "ai-plan-step__index";
            indexNode.textContent = String(index + 1).padStart(2, "0");

            const copy = document.createElement("span");
            copy.className = "ai-plan-step__copy";
            const title = document.createElement("strong");
            title.textContent = `${item.moduleId || "未知模块"} · ${item.queryId || "未知查询"}`;
            const detail = document.createElement("div");
            detail.className = "ai-query-result";
            if (item.status === "OK") {
                const view = findQueryDisplay(item, index);
                if (view) {
                    appendStructuredQueryView(detail, item.payload, view);
                } else {
                    appendQueryPayloadView(
                        detail,
                        buildQueryPayloadView(item.payload)
                    );
                }
            } else {
                detail.append(elementText(
                    "small",
                    "ai-plan-step__message",
                    item.message || queryStatusLabel(item.status)
                ));
            }
            copy.append(title, detail);

            const status = document.createElement("span");
            status.className = "ai-plan-step__status";
            status.dataset.status = visualStatus;
            status.textContent = queryStatusLabel(item.status);
            row.append(indexNode, copy, status);
            nodes.planList.append(row);
        });

        nodes.risk.dataset.risk = "READ_ONLY";
        nodes.risk.innerHTML = query?.status === "OK"
            ? `${icon("check-plain", 14)}<span>查询完成，未执行动作</span>`
            : query?.status === "TRUNCATED"
                ? `${icon("alert", 14)}<span>查询结果已截断，未执行动作</span>`
                : `${icon("alert", 14)}<span>查询未全部完成，未执行动作</span>`;
        nodes.run.disabled = true;
        updateSurfaces();
    }

    function queryStatusLabel(status) {
        return {
            OK: "成功",
            FAILED: "失败",
            TRUNCATED: "已截断"
        }[status] ?? "失败";
    }

    // renderPlan 只负责状态到视图的映射：错误、Query、澄清、执行结果和待确认计划
    // 互斥展示。真正的 Action/计划执行必须经过宿主回调，不能由渲染路径触发。
    function renderPlan() {
        if (dialog.error && !dialog.plan && !dialog.question && !dialog.query) {
            nodes.planEyebrow.textContent = "AI 暂不可用";
            nodes.planTitle.textContent = dialog.error;
            nodes.planList.replaceChildren();
            nodes.risk.dataset.risk = "READ_ONLY";
            nodes.risk.innerHTML = `${icon("alert", 14)}<span>未执行任何动作</span>`;
            nodes.run.disabled = true;
            updateSurfaces();
            return;
        }
        if (dialog.query) {
            renderQueryResult();
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

    // 先用变更后布局测量终点，再以旧尺寸和颜色作为首帧做“入口形变”；新动画会取消
    // 上一组动画，finished 回调还会核对句柄，防止旧动画把新状态提前标记为完成。
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

    // 状态入口集中触发一次派生渲染，并在浏览器完成布局后应用目标焦点。focus 参数
    // 仅指定面板内安全目标；隐藏或不存在的节点不会被强行聚焦。
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

    // 同时递增回合与运行代次，等于让所有在途响应失效；随后清空内容、延迟任务和
    // 非交互调用轨迹，确保新一轮对话不会继承上一轮的确认或进度。
    function clearPlan() {
        dialog.runGeneration += 1;
        dialog.requestGeneration += 1;
        dialog.plan = null;
        dialog.question = null;
        dialog.query = null;
        dialog.execution = null;
        dialog.error = "";
        dialog.busy = false;
        clearLater(dialog.collapseTimer);
        clearLater(dialog.completionTimer);
        dialog.collapseTimer = null;
        dialog.completionTimer = null;
        resetInvocationTracking();
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
        if ((!dialog.plan && !dialog.question && !dialog.error && !dialog.query)
            || ![
                "planning",
                "running",
                "completed",
                "question",
                "error",
                "query"
            ].includes(dialog.mode)) {
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

    // 进度气泡使用独立的显示/隐藏延迟，指针从入口移到浮层时会取消隐藏，避免鼠标
    // 穿过间隙时闪断；运行面板打开时不叠加浮层。
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

    // 内联凭据只从当前输入控件读取，成功后立即清空控件内容；失败会尽力清除宿主进程
    // 中的临时配置，但保留原始连接错误，不把它覆盖成清理错误。
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
                allowDeleteDirect: dialog.settings.allowDeleteDirect,
                allowSendQueryResultsToModel: dialog.settings.allowSendQueryResultsToModel
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

    // 每个自然语言回合递增 requestGeneration；较慢的旧响应即使晚到也会被丢弃。
    // clientRequestId 交给宿主去重，避免网络重试造成重复写入。
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
        dialog.plan = null;
        dialog.question = null;
        dialog.query = null;
        dialog.execution = null;
        resetInvocationTracking();
        setState({
            mode: "sending",
            composerOpen: true,
            panelOpen: false
        });
        try {
            const continueTurn = await beforeAiTurn({ goal });
            if (generation !== dialog.requestGeneration) return;
            if (!continueTurn) {
                dialog.busy = false;
                setState({
                    mode: "composing",
                    composerOpen: true,
                    panelOpen: false,
                    focus: nodes.composerInput
                });
                return;
            }
            beginInvocationTracking();
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
                dialog.query = null;
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
                dialog.query = null;
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
                dialog.query = null;
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
            if (response?.kind === "QUERY" || response?.kind === "VIEW") {
                const query = normalizeAiQueryResponse(response);
                if (!query.valid) {
                    throw new Error(query.error || "AI 查询结果格式非法。");
                }
                dialog.plan = null;
                dialog.question = null;
                dialog.execution = null;
                dialog.query = query;
                setState({
                    mode: "query",
                    composerOpen: true,
                    panelOpen: true,
                    focus: nodes.planClose
                });
                const notice = query.notice
                    || (query.status === "OK"
                        ? `已完成 ${query.queries.length} 条本地查询，未执行任何动作。`
                        : query.status === "TRUNCATED"
                            ? "查询结果已截断，未执行任何动作。"
                            : "查询未全部完成，未执行任何动作。");
                onNotice(notice, { error: query.status === "FAILED" });
                return;
            }
            throw new Error("AI 返回了未知结果类型。");
        } catch (error) {
            if (generation !== dialog.requestGeneration) return;
            dialog.busy = false;
            dialog.error = error?.message || "AI 请求失败";
            dialog.plan = null;
            dialog.question = null;
            dialog.query = null;
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

    // 调用轨迹是覆盖层上的纯视觉提示。目标不可见时短暂等待后跳过；每个动画都注册
    // 幂等 cleanup，并同时受动画结束和超时约束，防止取消异常遗留高亮节点。
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

    // 独立 AI 计划只在用户点击确认后调用宿主。模块轨迹由宿主实际 STEP 事件驱动，
    // 不在确认前预演；最终状态仅采信宿主返回的执行记录。
    async function confirmPlan() {
        if (!dialog.plan?.planId || dialog.busy) {
            return;
        }
        const generation = ++dialog.requestGeneration;
        dialog.busy = true;
        beginInvocationTracking();
        setState({
            mode: "running",
            composerOpen: true,
            panelOpen: true
        });
        try {
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
        if (["running", "planning", "failed", "question", "error", "query"].includes(dialog.mode)) {
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

    // 指针捕获保证 Dock 快速移动时仍能收到 move/up；捕获失败的合成事件由元素自身
    // 继续接收移动事件，拖动开始后页面坐标仍会被夹紧到视口。
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

    // 超过 6px 才把点击意图升级为拖动，避免轻触/键盘激活被误判；首次升级才记录自由
    // 位置并显示吸附目标，后续帧只更新几何，不重复持久化。
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

    // 结束拖动时只持久化最终自由位置；接近默认锚点则走吸附动画并清除自由位置。点击
    // 抑制窗口跨越动画尾帧，防止 pointerup 紧接的 click 误打开输入区。
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

    // 键盘移动与鼠标拖动共享同一位置模型：Shift 提供细步长；只有向默认锚点靠近且
    // 进入吸附半径时才播放吸附，反向移动不会被“吸住”。
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

    // 点击 Dock 外部只收起计划面板，保留已打开的输入区；这样用户可以在不中断
    // composing 状态的情况下查看主界面，符合输入区与计划面板可共存的设计。
    function onDocumentPointerDown(event) {
        if (!dialog.panelOpen || dock.contains(event.target)) {
            return;
        }
        collapsePlanOnly();
    }

    // 自由位置在视口变化后重新夹紧并持久化；默认锚点由 CSS 和布局函数重算。
    // 吸附动画期间暂停 resize 写入，避免两个几何源同时修改 CSS 变量。
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

    // Dock 内监听器绑定到外壳长期持有的节点；跨 document/window 的监听器在 destroy
    // 中显式移除，避免重复挂载产生多份拖拽、关闭面板和 resize 行为。
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

    // 页面隐藏时先取消动画并清空可取消任务，再移除调用轨迹高亮。状态与代次无需修改：
    // pagehide 后不会再有当前文档可见更新，destroy 会额外递增运行代次。
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
        handleInvocationEvent,
        setAiSettings(settings) {
            dialog.settings = normalizeSettings(settings);
            // 宿主清除临时凭据后，已展开的 Dock 立即回到输入 Key 的状态；折叠态不主动
            // 弹出面板，避免后台设置变化突然抢占焦点。
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
            // 先让在途计划确认结果失效，再停止动画/定时器并解除 document/window 事件。
            dialog.runGeneration += 1;
            stop();
            window.removeEventListener("pagehide", stop);
            window.removeEventListener("resize", onWindowResize);
            document.removeEventListener("pointerdown", onDocumentPointerDown);
        }
    };
}
