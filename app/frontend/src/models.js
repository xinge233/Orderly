export const RISK_LABEL = Object.freeze({
    READ_ONLY: "只读",
    WRITE: "写入",
    DELETE: "删除"
});

export const STATUS_LABEL = Object.freeze({
    WAITING: "等待",
    PENDING: "等待",
    RUNNING: "执行中",
    EXECUTING: "执行中",
    COMPLETED: "成功",
    SUCCESS: "成功",
    FAILED: "失败",
    REJECTED: "已拒绝",
    ROLLED_BACK: "已回滚",
    ROLLBACK: "回滚中",
    CANCELLED: "已取消",
    QUESTION: "需要澄清"
});

const LIST_KEYS = [
    "items",
    "entries",
    "files",
    "todos",
    "events",
    "calendar",
    "runs",
    "data"
];

/**
 * 兼容直接数组与常见 API 包裹结构。
 *
 * @param {unknown} payload 响应体
 * @param {string[]} keys 优先字段
 * @returns {Array<unknown>} 数组
 */
export function unwrapList(payload, keys = []) {
    if (Array.isArray(payload)) {
        return payload;
    }
    if (!payload || typeof payload !== "object") {
        return [];
    }
    for (const key of [...keys, ...LIST_KEYS]) {
        if (Array.isArray(payload[key])) {
            return payload[key];
        }
    }
    return [];
}

/**
 * 将计划响应规范化为界面模型。
 *
 * @param {unknown} payload 后端响应
 * @returns {object} 计划
 */
export function normalizePlan(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    const steps = unwrapList(source.steps, ["steps"]).map((step, index) => {
        const value = step && typeof step === "object" ? step : {};
        return {
            id: String(value.id ?? value.stepId ?? index + 1),
            title: String(value.title ?? value.label ?? value.actionId ?? `步骤 ${index + 1}`),
            moduleId: String(value.moduleId ?? value.module ?? ""),
            actionId: String(value.actionId ?? value.action ?? ""),
            risk: String(value.risk ?? value.riskLevel ?? "READ_ONLY").toUpperCase(),
            parameters: value.parameters ?? value.args ?? {},
            status: String(value.status ?? "WAITING").toUpperCase(),
            dependsOn: Array.isArray(value.dependsOn) ? value.dependsOn : []
        };
    });
    return {
        planId: String(source.planId ?? source.id ?? ""),
        kind: String(source.kind ?? (steps.length ? "PLAN" : "QUESTION")).toUpperCase(),
        goal: String(source.goal ?? source.summary ?? ""),
        message: String(source.message ?? source.reason ?? ""),
        assumptions: Array.isArray(source.assumptions)
            ? source.assumptions.map(String)
            : [],
        impact: flattenText(source.impact ?? source.estimatedImpact ?? ""),
        confidence: Number(source.confidence ?? 0),
        steps
    };
}

/**
 * 判断计划是否包含删除步骤。
 *
 * @param {object} plan 计划
 * @returns {boolean} 是否包含删除
 */
export function requiresDeletePhrase(plan) {
    return Boolean(plan?.steps?.some((step) => (
        String(step.risk).toUpperCase() === "DELETE"
        || /(?:^|\.)trash$/i.test(String(step.actionId))
    )));
}

/**
 * 计算确认按钮是否可用。
 *
 * @param {object} plan 计划
 * @param {{confirmed: boolean, phrase: string}} input 用户确认状态
 * @returns {{allowed: boolean, reason: string}} 判定
 */
export function validateConfirmation(plan, input) {
    if (!plan?.planId) {
        return { allowed: false, reason: "计划尚未生成" };
    }
    if (plan.kind === "QUESTION") {
        return { allowed: false, reason: "当前需要先补充信息" };
    }
    if (!input?.confirmed) {
        return { allowed: false, reason: "请先检查并勾选计划" };
    }
    if (requiresDeletePhrase(plan) && input.phrase !== "删除") {
        return { allowed: false, reason: "删除计划需输入“删除”" };
    }
    return { allowed: true, reason: "" };
}

/**
 * 合并并去重事件，按序号升序返回。
 *
 * @param {Array<object>} current 已有事件
 * @param {Array<object>} incoming 新事件
 * @returns {Array<object>} 有序事件
 */
export function mergeEvents(current = [], incoming = []) {
    const map = new Map();
    [...current, ...incoming].forEach((raw, index) => {
        const event = normalizeEvent(raw, index);
        const key = event.sequence == null
            ? `${event.id ?? "event"}:${event.planId}:${event.stepId}:${event.status}:${index}`
            : `seq:${event.sequence}`;
        const existing = map.get(key);
        if (!existing || event.sequence == null || existing.sequence == null
            || event.sequence >= existing.sequence) {
            map.set(key, event);
        }
    });
    return [...map.values()].sort((left, right) => {
        if (left.sequence != null && right.sequence != null) {
            return left.sequence - right.sequence;
        }
        return left.timeValue - right.timeValue;
    });
}

/**
 * 兼容后端事件字段。
 *
 * @param {unknown} raw 原始事件
 * @param {number} fallbackIndex 缺省索引
 * @returns {object} 事件
 */
export function normalizeEvent(raw, fallbackIndex = 0) {
    const source = raw && typeof raw === "object" ? raw : {};
    const sequenceValue = source.sequence ?? source.seq ?? source.eventSeq;
    const sequence = Number.isFinite(Number(sequenceValue))
        ? Number(sequenceValue)
        : null;
    const timeText = String(source.timestamp ?? source.createdAt ?? source.time ?? "");
    const parsedTime = Date.parse(timeText);
    return {
        id: source.id == null ? null : String(source.id),
        sequence,
        planId: String(source.planId ?? source.runId ?? source.taskId ?? ""),
        stepId: String(source.stepId ?? source.step ?? ""),
        title: String(
            source.title
            ?? source.message
            ?? source.actionId
            ?? `事件 ${sequence ?? fallbackIndex + 1}`
        ),
        message: String(source.message ?? source.detail ?? ""),
        status: String(source.status ?? "COMPLETED").toUpperCase(),
        timestamp: timeText,
        timeValue: Number.isFinite(parsedTime) ? parsedTime : fallbackIndex
    };
}

/**
 * 把任意影响说明压成一行安全文本。
 *
 * @param {unknown} value 值
 * @returns {string} 文本
 */
export function flattenText(value) {
    if (value == null) {
        return "";
    }
    if (Array.isArray(value)) {
        return value.map(flattenText).filter(Boolean).join("；");
    }
    if (typeof value === "object") {
        return Object.entries(value)
            .map(([key, item]) => {
                const text = flattenText(item);
                return text ? `${key}：${text}` : "";
            })
            .filter(Boolean)
            .join("；");
    }
    return String(value);
}

/**
 * 人类可读的文件大小。
 *
 * @param {unknown} input 字节数
 * @returns {string} 大小
 */
export function formatBytes(input) {
    const bytes = Number(input);
    if (!Number.isFinite(bytes) || bytes < 0) {
        return "—";
    }
    if (bytes < 1024) {
        return `${bytes} B`;
    }
    const units = ["KB", "MB", "GB", "TB"];
    let value = bytes / 1024;
    let index = 0;
    while (value >= 1024 && index < units.length - 1) {
        value /= 1024;
        index++;
    }
    return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`;
}

/**
 * 格式化时间。
 *
 * @param {unknown} input ISO 时间
 * @returns {string} 本地时间
 */
export function formatDate(input) {
    if (!input) {
        return "未设置";
    }
    const date = new Date(input);
    if (Number.isNaN(date.getTime())) {
        return String(input);
    }
    return new Intl.DateTimeFormat("zh-CN", {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit"
    }).format(date);
}

/**
 * 合并安全的工作区相对路径。
 *
 * @param {string} base 根路径
 * @param {string} child 子路径
 * @returns {string} 相对路径
 */
export function joinPath(base, child) {
    const cleanChild = String(child ?? "").replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
    const cleanBase = String(base ?? "").replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
    return [cleanBase, cleanChild].filter(Boolean).join("/");
}

/**
 * 从路径返回上一级。
 *
 * @param {string} path 相对路径
 * @returns {string} 父路径
 */
export function parentPath(path) {
    const parts = String(path ?? "").replaceAll("\\", "/").split("/").filter(Boolean);
    parts.pop();
    return parts.join("/");
}

/**
 * 判定条目是否为目录。
 *
 * @param {object} entry 文件条目
 * @returns {boolean} 是否目录
 */
export function isDirectoryEntry(entry) {
    return Boolean(entry?.directory ?? entry?.isDirectory ?? entry?.type === "DIRECTORY");
}

/**
 * 统一状态标签。
 *
 * @param {string} status 状态
 * @returns {string} 中文状态
 */
export function statusLabel(status) {
    const key = String(status ?? "").toUpperCase();
    return STATUS_LABEL[key] ?? (key.replaceAll("_", " ") || "未知");
}
