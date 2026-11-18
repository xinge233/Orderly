export const RISK_LABEL = Object.freeze({
    READ_ONLY: "只读",
    WRITE: "写入",
    DELETE: "删除"
});

export const STATUS_LABEL = Object.freeze({
    PARTIAL: "部分完成",
    WAITING: "等待",
    PENDING: "等待",
    RUNNING: "执行中",
    EXECUTING: "执行中",
    OK: "成功",
    COMPLETED: "成功",
    SUCCESS: "成功",
    SUCCEEDED: "成功",
    FAILED: "失败",
    TRUNCATED: "已截断",
    REJECTED: "已拒绝",
    ROLLED_BACK: "已回滚",
    ROLLBACK: "回滚中",
    ROLLBACK_FAILED: "回滚失败",
    CANCELLED: "已取消",
    QUESTION: "需要澄清"
});

export const AI_QUERY_LIMIT = 4;

const AI_QUERY_RESULT_STATUSES = new Set(["OK", "FAILED", "TRUNCATED"]);
const QUERY_PAYLOAD_SUMMARY_LIMIT = 240;

export const EVENT_ORIGIN = Object.freeze({
    PLAN: "PLAN",
    AI_DIRECT: "AI_DIRECT",
    AI_PLAN: "AI_PLAN",
    MODULE_CONTROL: "MODULE_CONTROL",
    SYSTEM: "SYSTEM"
});

export const EVENT_ORIGIN_LABEL = Object.freeze({
    [EVENT_ORIGIN.PLAN]: "计划",
    [EVENT_ORIGIN.AI_DIRECT]: "AI 直接执行",
    [EVENT_ORIGIN.AI_PLAN]: "AI 计划",
    [EVENT_ORIGIN.MODULE_CONTROL]: "模块控制",
    [EVENT_ORIGIN.SYSTEM]: "系统"
});

const LIST_KEYS = [
    "items",
    "entries",
    "events",
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
 * 将本地查询结果压成有界、可安全渲染的单行摘要。
 *
 * @param {unknown} payload 查询结果
 * @param {number} limit 最大字符数
 * @returns {string} 摘要
 */
export function summarizeQueryPayload(payload, limit = QUERY_PAYLOAD_SUMMARY_LIMIT) {
    if (payload == null) {
        return "无结果内容";
    }
    const maximum = Number.isFinite(Number(limit))
        ? Math.max(16, Math.min(1000, Math.trunc(Number(limit))))
        : QUERY_PAYLOAD_SUMMARY_LIMIT;
    let serialized = "";
    if (typeof payload === "string") {
        serialized = payload;
    } else {
        const seen = new WeakSet();
        try {
            serialized = JSON.stringify(payload, (_key, value) => {
                if (typeof value === "bigint") {
                    return String(value);
                }
                if (value && typeof value === "object") {
                    if (seen.has(value)) {
                        return "[循环]";
                    }
                    seen.add(value);
                }
                return value;
            });
        } catch {
            serialized = String(payload);
        }
    }
    const text = String(serialized ?? "").replace(/\s+/g, " ").trim() || "空结果";
    return text.length > maximum
        ? `${text.slice(0, maximum - 1)}…`
        : text;
}

function boundedQueryText(value, limit = 600) {
    return String(value ?? "").replace(/\s+/g, " ").trim().slice(0, limit);
}

function sameQueryIdentity(result, moduleId, queryId) {
    return Boolean(
        result
        && typeof result === "object"
        && String(result.moduleId ?? "").trim() === moduleId
        && String(result.queryId ?? "").trim() === queryId
    );
}

function queryResultMessage(status) {
    return {
        OK: "查询完成",
        FAILED: "查询失败",
        TRUNCATED: "查询结果已截断"
    }[status];
}

/**
 * 规范化 AI 本地查询响应。只展示前四条查询，非 OK 结果不保留 payload。
 *
 * @param {unknown} payload /api/ai/turns 的 QUERY 响应
 * @returns {{
 *     kind: "QUERY",
 *     valid: boolean,
 *     degraded: boolean,
 *     status: "OK" | "FAILED" | "TRUNCATED",
 *     message: string,
 *     queries: Array<object>,
 *     error?: string,
 *     notice?: string
 * }} 查询模型
 */
export function normalizeAiQueryResponse(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    const message = boundedQueryText(source.message);
    if (!Array.isArray(source.queries) || source.queries.length === 0) {
        return {
            kind: "QUERY",
            valid: false,
            degraded: true,
            status: "FAILED",
            message,
            queries: [],
            error: "AI 查询结果格式非法：queries 必须包含 1 至 4 条查询。"
        };
    }

    const rawResults = Array.isArray(source.queryResults) ? source.queryResults : [];
    const usedResults = new Set();
    let degraded = source.queries.length > AI_QUERY_LIMIT || !Array.isArray(source.queryResults);
    const queries = source.queries.slice(0, AI_QUERY_LIMIT).map((rawQuery, index) => {
        const query = rawQuery && typeof rawQuery === "object" ? rawQuery : {};
        const moduleId = boundedQueryText(query.moduleId, 128);
        const queryId = boundedQueryText(query.queryId, 160);
        if (!moduleId || !queryId) {
            degraded = true;
            return {
                moduleId,
                queryId,
                status: "FAILED",
                message: "查询描述不完整，未展示本地结果。"
            };
        }

        let resultIndex = -1;
        if (!usedResults.has(index)
            && sameQueryIdentity(rawResults[index], moduleId, queryId)) {
            resultIndex = index;
        } else {
            resultIndex = rawResults.findIndex((result, candidateIndex) =>
                !usedResults.has(candidateIndex)
                && sameQueryIdentity(result, moduleId, queryId));
        }
        if (resultIndex < 0) {
            degraded = true;
            return {
                moduleId,
                queryId,
                status: "FAILED",
                message: "宿主未返回该查询的可展示结果。"
            };
        }

        usedResults.add(resultIndex);
        const result = rawResults[resultIndex];
        const statusText = String(result.status ?? "").trim().toUpperCase();
        if (!AI_QUERY_RESULT_STATUSES.has(statusText)) {
            degraded = true;
            return {
                moduleId,
                queryId,
                status: "FAILED",
                message: "宿主返回了无法识别的查询状态，未展示本地结果。"
            };
        }
        const resultMessage = boundedQueryText(result.message)
            || queryResultMessage(statusText);
        return {
            moduleId,
            queryId,
            status: statusText,
            message: resultMessage,
            ...(statusText === "OK" && Object.hasOwn(result, "payload")
                ? { payload: result.payload }
                : {})
        };
    });

    const aggregateStatus = queries.some((query) => query.status === "FAILED")
        ? "FAILED"
        : queries.some((query) => query.status === "TRUNCATED")
            ? "TRUNCATED"
            : "OK";
    const status = source.queries.length > AI_QUERY_LIMIT && aggregateStatus === "OK"
        ? "TRUNCATED"
        : aggregateStatus;
    const notice = source.queries.length > AI_QUERY_LIMIT
        ? "宿主返回的查询超过 4 条，界面仅展示前 4 条。"
        : !Array.isArray(source.queryResults)
            ? "宿主未返回有效的查询结果列表，查询项已安全降级为失败。"
            : degraded
                ? "部分查询结果无法展示，已按失败状态安全降级。"
                : "";
    return {
        kind: "QUERY",
        valid: true,
        degraded,
        status,
        message,
        queries,
        ...(notice ? { notice } : {})
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
        origin: inferEventOrigin(source),
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
 * 兼容新事件来源字段，并为旧事件按类型或计划标识推断来源。
 *
 * @param {unknown} raw 原始事件或执行记录
 * @returns {string} 规范化来源
 */
export function inferEventOrigin(raw) {
    const source = raw && typeof raw === "object" ? raw : {};
    const explicit = normalizeOrigin(source.origin);
    if (explicit) {
        return explicit;
    }
    const type = String(
        source.type
        ?? source.eventType
        ?? source.category
        ?? source.kind
        ?? ""
    ).toUpperCase();
    if (type.includes("AI_DIRECT") || type === "DIRECT") {
        return EVENT_ORIGIN.AI_DIRECT;
    }
    if (type.includes("AI_PLAN")) {
        return EVENT_ORIGIN.AI_PLAN;
    }
    if (type.includes("MODULE_CONTROL")) {
        return EVENT_ORIGIN.MODULE_CONTROL;
    }
    if (type.includes("SYSTEM")) {
        return EVENT_ORIGIN.SYSTEM;
    }
    if (type.includes("PLAN") || source.planId) {
        return EVENT_ORIGIN.PLAN;
    }
    return EVENT_ORIGIN.SYSTEM;
}

function normalizeOrigin(value) {
    const normalized = String(value ?? "").trim().toUpperCase().replace(/[\s-]+/g, "_");
    return Object.hasOwn(EVENT_ORIGIN, normalized) ? normalized : "";
}

/**
 * 将执行记录规范化为历史摘要行。
 *
 * @param {unknown} raw 原始记录
 * @param {number} fallbackIndex 缺省索引
 * @returns {object} 执行记录
 */
export function normalizeExecutionRecord(raw, fallbackIndex = 0) {
    const source = raw && typeof raw === "object" ? raw : {};
    const status = String(source.status ?? "COMPLETED").toUpperCase();
    const executionId = String(
        source.executionId
        ?? source.executionID
        ?? source.id
        ?? ""
    );
    const canRollbackValue = source.canRollback
        ?? source.rollbackAvailable
        ?? source.rollbackable;
    return {
        executionId,
        displayId: String(source.executionId ?? source.id ?? source.planId ?? fallbackIndex + 1),
        planId: String(source.planId ?? ""),
        origin: inferEventOrigin(source),
        goal: String(source.goal ?? source.title ?? source.message ?? "未命名执行"),
        message: String(source.message ?? source.summary ?? ""),
        status,
        createdAt: String(source.createdAt ?? source.finishedAt ?? source.timestamp ?? source.time ?? ""),
        canRollback: typeof canRollbackValue === "boolean"
            ? canRollbackValue
            : ["COMPLETED", "SUCCESS", "SUCCEEDED", "PARTIAL"].includes(status),
        rollbackDisabledReason: String(
            source.rollbackDisabledReason
            ?? source.rollbackMessage
            ?? ""
        )
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
