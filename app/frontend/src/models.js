/**
 * 宿主契约与执行记录的前端规范化模型。
 *
 * 职责：把计划、AI Query、事件、执行记录和文件展示数据收敛为稳定、可渲染的界面结构，
 * 兼容宿主字段别名，并在数据不完整时采用保守降级。这里不执行授权、不调用 API，也不
 * 把清洗后的数据反写为契约事实。
 *
 * 安全边界：规范化只保证展示层可控，不替代 Java 宿主的契约校验。Query payload 仅在
 * `OK` 状态下保留给本地界面；失败、截断或无法识别身份的结果不会进入展示模型。
 *
 * 除函数注释另有说明外，本文件导出函数不主动抛出业务异常且不产生副作用；遇到缺失或
 * 畸形宿主字段时优先返回可渲染的降级模型。
 */
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
const QUERY_VIEW_LAYOUTS = new Set(["keyValue", "list", "table", "text", "timeline"]);
const QUERY_VIEW_FORMATS = new Set([
    "text",
    "status",
    "date",
    "time",
    "datetime",
    "number",
    "boolean"
]);
const QUERY_VIEW_ROLES = new Set(["time", "primary", "secondary", "status"]);
const QUERY_VIEW_TIME_FORMATS = new Set(["date", "time", "datetime"]);
const QUERY_VIEW_DENSITIES = new Set(["compact", "comfortable"]);
const QUERY_VIEW_GROUP_BY = new Set(["none", "date"]);
const QUERY_VIEW_MAX_FIELDS = 12;
const QUERY_FIELD_PATH = /^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;

export const EVENT_ORIGIN = Object.freeze({
    PLAN: "PLAN",
    AI_DIRECT: "AI_DIRECT",
    AI_QUERY: "AI_QUERY",
    AI_PLAN: "AI_PLAN",
    MODULE_CONTROL: "MODULE_CONTROL",
    SYSTEM: "SYSTEM"
});

export const EVENT_ORIGIN_LABEL = Object.freeze({
    [EVENT_ORIGIN.PLAN]: "计划",
    [EVENT_ORIGIN.AI_DIRECT]: "AI 直接执行",
    [EVENT_ORIGIN.AI_QUERY]: "AI 查询",
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
 *
 * 该兼容层只读取直接子字段，不做递归搜索，避免把嵌套业务数据误当作事件或模块列表。
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
 *
 * 缺失字段会给出可渲染占位值；风险等级和状态统一转为大写，但不会因为出现在步骤中
 * 就视为已获授权。确认与否仍由 `validateConfirmation()` 和宿主共同判断。
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
 *
 * 摘要限制在 16 至 1000 字符，并处理 BigInt、循环引用和常见序列化失败，避免畸形查询
 * 结果阻断本地视图。
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

/**
 * 校验一条结果确实属于声明的模块与 Query。
 *
 * @param {unknown} result 候选查询结果
 * @param {string} moduleId 期望模块 ID
 * @param {string} queryId 期望查询 ID
 * @returns {boolean} 身份是否匹配
 *
 * 异常与副作用：无。只做字符串归一后比较，结果内容本身不参与身份推断。
 */
function sameQueryIdentity(result, moduleId, queryId) {
    return Boolean(
        result
        && typeof result === "object"
        && String(result.moduleId ?? "").trim() === moduleId
        && String(result.queryId ?? "").trim() === queryId
    );
}

/**
 * 为合法 Query 状态提供稳定的中文兜底消息。
 *
 * @param {"OK" | "FAILED" | "TRUNCATED"} status 查询状态
 * @returns {string | undefined} 状态说明
 *
 * 异常与副作用：无；非法状态由调用方先行过滤，不会进入此映射。
 */
function queryResultMessage(status) {
    return {
        OK: "查询完成",
        FAILED: "查询失败",
        TRUNCATED: "查询结果已截断"
    }[status];
}

/**
 * 按点分隔字段读取已有 payload。该函数不执行表达式，也不接受数组索引。
 *
 * @param {unknown} source payload
 * @param {string} path 字段路径
 * @returns {unknown} 字段值或 undefined
 */
export function resolveQueryPath(source, path) {
    const parts = String(path ?? "").split(".").filter(Boolean);
    if (!parts.length || !QUERY_FIELD_PATH.test(parts.join("."))) {
        return undefined;
    }
    let current = source;
    for (const part of parts) {
        if (!current || typeof current !== "object" || Array.isArray(current)) {
            return undefined;
        }
        if (!Object.hasOwn(current, part)) {
            return undefined;
        }
        current = current[part];
    }
    return current;
}

/**
 * 把 AI 或模块展示声明规范化为受限视图模型。
 *
 * @param {unknown} rawView 原始展示描述
 * @param {unknown} sourcePayload 对应 Query payload
 * @returns {object | null} 合法视图或 null
 */
export function normalizeQueryView(rawView, sourcePayload, displayHints = null) {
    const source = rawView && typeof rawView === "object" ? rawView : {};
    const hints = displayHints && typeof displayHints === "object" ? displayHints : {};
    const sourceQuery = Number(source.sourceQuery);
    const layout = String(source.layout ?? hints.fallbackLayout ?? "").trim();
    const title = boundedQueryText(source.title, 200);
    if (!Number.isInteger(sourceQuery) || sourceQuery < 1 || sourceQuery > AI_QUERY_LIMIT
        || !QUERY_VIEW_LAYOUTS.has(layout) || !title) {
        return null;
    }
    const rowPathValue = source.rowPath ?? hints.rowPath;
    const rowPath = rowPathValue == null ? "" : String(rowPathValue).trim();
    if (rowPath && !QUERY_FIELD_PATH.test(rowPath)) {
        return null;
    }
    const rowSource = rowPath ? resolveQueryPath(sourcePayload, rowPath) : sourcePayload;
    if (["list", "table", "timeline"].includes(layout) && !Array.isArray(rowSource)) {
        return null;
    }
    const sample = Array.isArray(rowSource) ? rowSource[0] : rowSource;
    const rawFields = Array.isArray(source.fields)
        ? source.fields
        : Array.isArray(hints.fields)
            ? hints.fields
            : [];
    const rawDetailFields = Array.isArray(source.detailFields)
        ? source.detailFields
        : Array.isArray(hints.detailFields)
            ? hints.detailFields
            : [];
    if (Array.isArray(rowSource) && rowSource.length === 0
        && (rawFields.length > 0 || rawDetailFields.length > 0)) {
        return null;
    }
    const fieldHints = new Map(
        [...(Array.isArray(hints.fields) ? hints.fields : []),
            ...(Array.isArray(hints.detailFields) ? hints.detailFields : [])]
            .filter((field) => field && typeof field === "object")
            .map((field) => [String(field.key ?? "").trim(), field])
    );
    const normalizeFields = (rawValues) => rawValues
        .slice(0, QUERY_VIEW_MAX_FIELDS)
        .flatMap((rawField) => {
        const field = rawField && typeof rawField === "object" ? rawField : {};
        const key = String(field.key ?? "").trim();
        const label = boundedQueryText(field.label, 80);
        const format = String(field.format ?? "").trim();
        if (!QUERY_FIELD_PATH.test(key) || !label || !QUERY_VIEW_FORMATS.has(format)) {
            return [];
        }
        if (sample && typeof sample === "object"
            && resolveQueryPath(sample, key) === undefined) {
            return [];
        }
        const hint = fieldHints.get(key) || {};
        const rawRole = String(field.role ?? hint.role ?? "").trim();
        if (rawRole && !QUERY_VIEW_ROLES.has(rawRole)) {
            return [];
        }
        const role = QUERY_VIEW_ROLES.has(rawRole) ? rawRole : "";
        if (role === "time" && !QUERY_VIEW_TIME_FORMATS.has(format)) {
            return [];
        }
        const valueLabels = normalizeValueLabels(hint.valueLabels);
        return [{ key, label, format, role, valueLabels }];
    });
    const fields = normalizeFields(rawFields);
    if (rawFields.length > 0 && fields.length === 0) {
        return null;
    }
    const detailFields = normalizeFields(rawDetailFields);
    if (rawDetailFields.length > 0 && detailFields.length === 0) {
        return null;
    }
    const density = String(source.density ?? hints.density ?? "comfortable").trim();
    const groupBy = String(source.groupBy ?? hints.groupBy ?? "none").trim();
    if (!QUERY_VIEW_DENSITIES.has(density) || !QUERY_VIEW_GROUP_BY.has(groupBy)) {
        return null;
    }
    const hideRepeatedFields = typeof source.hideRepeatedFields === "boolean"
        ? source.hideRepeatedFields
        : hints.hideRepeatedFields === true;
    if (source.hideRepeatedFields != null && typeof source.hideRepeatedFields !== "boolean") {
        return null;
    }
    const rawEmphasis = Array.isArray(source.emphasisFields)
        ? source.emphasisFields
        : Array.isArray(hints.emphasisFields)
            ? hints.emphasisFields
            : [];
    const availableKeys = new Set([...fields, ...detailFields].map((field) => field.key));
    const normalizedEmphasis = rawEmphasis.map((key) => String(key).trim());
    if (rawEmphasis.length > 2
        || new Set(normalizedEmphasis).size !== normalizedEmphasis.length
        || normalizedEmphasis.some((key) => !availableKeys.has(key))) {
        return null;
    }
    const emphasisFields = normalizedEmphasis;
    const hasTimeRole = fields.some((field) => field.role === "time");
    if (["list", "table", "timeline"].includes(layout) && !rowPath) {
        return null;
    }
    if (layout === "timeline"
        && (!hasTimeRole || !fields.some((field) => field.role === "primary"))) {
        return null;
    }
    if ((layout === "timeline" || groupBy === "date") && !hasTimeRole) {
        return null;
    }
    return {
        sourceQuery,
        layout,
        title,
        rowPath,
        fields,
        detailFields,
        emptyText: boundedQueryText(source.emptyText ?? hints.emptyText, 200) || "暂无数据",
        density,
        groupBy,
        hideRepeatedFields,
        emphasisFields
    };
}

function normalizeValueLabels(rawValue) {
    if (!rawValue || typeof rawValue !== "object" || Array.isArray(rawValue)) {
        return {};
    }
    return Object.fromEntries(
        Object.entries(rawValue)
            .slice(0, 64)
            .flatMap(([key, value]) => {
                const normalizedKey = String(key).trim().slice(0, 80);
                const normalizedValue = boundedQueryText(value, 80);
                return normalizedKey && normalizedValue
                    ? [[normalizedKey, normalizedValue]]
                    : [];
            })
    );
}

/**
 * 按受控格式转换单个字段值。未知格式退回文本。
 *
 * @param {unknown} value 字段值
 * @param {string} format 字段格式
 * @returns {string} 可展示文本
 */
export function formatQueryValue(value, format = "text", valueLabels = null) {
    if (value == null || value === "") {
        return "未返回数据";
    }
    if (format === "boolean") {
        return value === true ? "是" : value === false ? "否" : String(value);
    }
    if (format === "status") {
        const labels = valueLabels && typeof valueLabels === "object" ? valueLabels : {};
        const key = String(value);
        return Object.hasOwn(labels, key) ? labels[key] : key;
    }
    if (format === "date" || format === "time" || format === "datetime") {
        const parsed = Date.parse(String(value));
        if (Number.isFinite(parsed)) {
            const options = format === "date"
                ? { dateStyle: "medium" }
                : format === "time"
                    ? { hour: "2-digit", minute: "2-digit" }
                    : { dateStyle: "medium", timeStyle: "short" };
            return new Intl.DateTimeFormat("zh-CN", options).format(new Date(parsed));
        }
        return "时间格式无效";
    }
    if (typeof value === "object") {
        return summarizeQueryPayload(value);
    }
    return String(value);
}

/**
 * 生成前端展示所需的只读 payload 树，限制深度、条目和文本长度。
 *
 * @param {unknown} payload Query payload
 * @param {{depth?: number, seen?: WeakSet<object>, maxDepth?: number, maxItems?: number, maxText?: number}} [options] 限制
 * @returns {object} 展示树
 */
export function buildQueryPayloadView(payload, options = {}) {
    const depth = Number(options.depth ?? 0);
    const maxDepth = Number(options.maxDepth ?? 3);
    const maxItems = Number(options.maxItems ?? 8);
    const maxText = Number(options.maxText ?? 240);
    const seen = options.seen ?? new WeakSet();
    if (payload == null || payload === "") {
        return { type: "empty", text: "未返回数据" };
    }
    if (typeof payload !== "object") {
        return {
            type: "scalar",
            value: boundedQueryText(formatQueryValue(payload), maxText)
        };
    }
    if (seen.has(payload)) {
        return { type: "scalar", value: "[循环引用]" };
    }
    if (depth >= maxDepth) {
        return {
            type: "scalar",
            value: Array.isArray(payload) ? `[${payload.length} 项]` : "[嵌套对象]"
        };
    }
    seen.add(payload);
    if (Array.isArray(payload)) {
        return {
            type: "array",
            count: payload.length,
            items: payload.slice(0, maxItems).map((item) =>
                buildQueryPayloadView(item, {
                    ...options,
                    depth: depth + 1,
                    seen,
                    maxDepth,
                    maxItems,
                    maxText
                })
            ),
            truncated: payload.length > maxItems
        };
    }
    const entries = Object.entries(payload);
    return {
        type: "object",
        count: entries.length,
        entries: entries.slice(0, maxItems).map(([key, value]) => ({
            key: boundedQueryText(key, 80),
            value: buildQueryPayloadView(value, {
                ...options,
                depth: depth + 1,
                seen,
                maxDepth,
                maxItems,
                maxText
            })
        })),
        truncated: entries.length > maxItems
    };
}

/**
 * 规范化 AI 本地查询或展示整理响应。只展示前四条查询，非 OK 结果不保留 payload。
 *
 * @param {unknown} payload /api/ai/turns 的 QUERY 响应
 * @returns {{
 *     kind: "QUERY" | "VIEW",
 *     valid: boolean,
 *     degraded: boolean,
 *     status: "OK" | "FAILED" | "TRUNCATED",
 *     message: string,
 *     queries: Array<object>,
 *     view?: object,
 *     error?: string,
 *     notice?: string
 * }} 查询模型
 *
 * 不变量：最多展示 4 条查询；声明顺序优先匹配同下标的宿主结果，再按身份查找未被占用的
 * 结果；任何一条失败都使聚合状态为 `FAILED`，截断优先于成功。非 `OK` 结果即使带有
 * payload 也会被丢弃，避免失败详情或敏感内容绕过本地展示策略。
 *
 * 异常与副作用：无；格式错误会降级为 `valid: false` 或单条失败，而不是向 UI 抛出异常。
 */
export function normalizeAiQueryResponse(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    const kind = source.kind === "VIEW" ? "VIEW" : "QUERY";
    const message = boundedQueryText(source.message);
    const rawResults = Array.isArray(source.queryResults) ? source.queryResults : [];
    const declaredQueries = Array.isArray(source.queries)
        ? source.queries
        : kind === "VIEW"
            ? rawResults.map((result) => ({
                moduleId: result?.moduleId,
                queryId: result?.queryId,
                arguments: {}
            }))
            : [];
    if (declaredQueries.length === 0) {
        return {
            kind,
            valid: false,
            degraded: true,
            status: "FAILED",
            message,
            queries: [],
            error: "AI 查询结果格式非法：queries 必须包含 1 至 4 条查询。"
        };
    }

    const usedResults = new Set();
    let degraded = declaredQueries.length > AI_QUERY_LIMIT
        || !Array.isArray(source.queryResults);
    const queries = declaredQueries.slice(0, AI_QUERY_LIMIT).map((rawQuery, index) => {
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
    const status = declaredQueries.length > AI_QUERY_LIMIT && aggregateStatus === "OK"
        ? "TRUNCATED"
        : aggregateStatus;
    const viewSource = source.view && typeof source.view === "object" ? source.view : null;
    const sourceResult = rawResults[Number(viewSource?.sourceQuery) - 1];
    const view = viewSource && sourceResult?.status === "OK"
        ? normalizeQueryView(viewSource, sourceResult?.payload)
        : null;
    const invalidView = kind === "VIEW" && viewSource && !view;
    const missingView = kind === "VIEW" && !viewSource;
    const notice = declaredQueries.length > AI_QUERY_LIMIT
        ? "宿主返回的查询超过 4 条，界面仅展示前 4 条。"
        : !Array.isArray(source.queryResults)
            ? "宿主未返回有效的查询结果列表，查询项已安全降级为失败。"
            : degraded
                ? "部分查询结果无法展示，已按失败状态安全降级。"
                : invalidView || missingView
                    ? "AI 展示整理无效，已回退到通用查询结果视图。"
                    : "";
    return {
        kind,
        valid: true,
        degraded: degraded || invalidView || missingView,
        status,
        message,
        queries,
        ...(viewSource ? { viewSource } : {}),
        ...(view ? { view } : {}),
        ...(notice ? { notice } : {})
    };
}

/**
 * 判断计划是否包含删除步骤。
 *
 * @param {object} plan 计划
 * @returns {boolean} 是否包含删除
 *
 * 风险等级或 `*.trash` 动作任一命中即要求确认词。双条件采用保守策略：即使模块只错误
 * 标记了其中一项，也不能绕过更强的删除确认。
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
 *
 * 这里产出的是界面按钮可用性，不是执行授权；宿主必须再次校验计划状态、确认和删除策略。
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
 *
 * 有 `sequence` 的事件按序号去重并以后出现的同序号事件为准；缺少序号的事件保留其组合
 * 键。最终按序号或回退时间升序排列，使 SSE 重连和初次拉取可以安全合并。
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
 *
 * 无有效时间戳时使用调用方提供的索引作为稳定排序值，而不是当前时间，避免同一批旧事件
 * 每次加载产生不同次序。
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
        type: String(source.type ?? source.eventType ?? "").toUpperCase(),
        planId: String(source.planId ?? source.runId ?? source.taskId ?? ""),
        stepId: String(source.stepId ?? source.step ?? ""),
        moduleId: String(source.moduleId ?? source.module ?? ""),
        actionId: String(source.actionId ?? source.action ?? ""),
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
 *
 * 显式来源优先；旧记录只能依赖类型字符串或 `planId` 推断，无法判定时保守归入 `SYSTEM`，
 * 不伪造为计划或 AI 直执。
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
    if (type.includes("AI_QUERY")) {
        return EVENT_ORIGIN.AI_QUERY;
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

/**
 * 将来源别名转为受控枚举值。
 *
 * @param {unknown} value 原始来源
 * @returns {string} 合法来源或空字符串
 *
 * 异常与副作用：无；仅接受 `EVENT_ORIGIN` 自身声明的键，大小写、空格和连字符会被归一。
 */
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
 *
 * `canRollback` 在宿主未明确给出时，仅对已成功或部分完成的终态做乐观展示；真正可回滚性
 * 仍由宿主接口决定，点击后不能依赖该界面默认值。
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
 *
 * 数组和对象会被递归拼接为可读单行文本；返回值未做 HTML 转义，调用方应通过文本节点渲染。
 * 函数不检测循环引用，输入应限制为 JSON 形状的数据，否则可能因无限递归抛出 `RangeError`。
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
 *
 * 非法或负值返回破折号；超过 10 个当前单位时省略小数，以降低文件列表的视觉噪声。
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
 *
 * 无效时间原样转为字符串，既不猜测时区，也不把解析失败误显示为当前时间。
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
 * 以词法方式合并工作区相对路径。
 *
 * @param {string} base 根路径
 * @param {string} child 子路径
 * @returns {string} 相对路径
 *
 * 该函数只统一分隔符并去掉首尾斜杠，不处理 `.`、`..`，也不构成沙箱边界；真实路径授权
 * 必须由宿主在访问文件系统前完成。
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
 *
 * 仅做展示层词法计算，不读取目录，也不会验证结果是否仍位于某工作区内。
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
 *
 * 未知状态保留可读的英文形式作为诊断信息，不伪造为成功或失败。
 */
export function statusLabel(status) {
    const key = String(status ?? "").toUpperCase();
    return STATUS_LABEL[key] ?? (key.replaceAll("_", " ") || "未知");
}
