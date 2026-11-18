export const AI_MODES = Object.freeze([
    { value: "DEEPSEEK", label: "DeepSeek" },
    { value: "CUSTOM", label: "自定义" }
]);

const AI_MODE_VALUES = new Set(AI_MODES.map((item) => item.value));

/**
 * 清洗宿主返回的 AI 设置；响应永远不会包含 API Key。
 *
 * @param {unknown} payload /api/ai/settings 响应
 * @returns {{mode: string, baseUrl: string, model: string, configured: boolean, allowDeleteDirect: boolean, allowSendQueryResultsToModel: boolean, allowLocalProvider: boolean}} 设置
 */
export function normalizeAiSettings(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    return {
        mode: AI_MODE_VALUES.has(source.mode) ? source.mode : "DEEPSEEK",
        baseUrl: typeof source.baseUrl === "string" ? source.baseUrl : "",
        model: typeof source.model === "string" ? source.model : "",
        configured: source.configured === true,
        allowDeleteDirect: source.allowDeleteDirect === true,
        allowSendQueryResultsToModel: source.allowSendQueryResultsToModel === true,
        allowLocalProvider: source.allowLocalProvider === true
    };
}

/**
 * 校验临时 AI 设置草稿。
 *
 * @param {{mode: string, apiKey: string, baseUrl: string, model: string}} draft 草稿
 * @param {{configured?: boolean, allowLocalProvider?: boolean}} options 当前宿主状态
 * @returns {string} 错误消息；合法时返回空字符串
 */
export function validateAiSettingsDraft(draft, {
    configured = false,
    allowLocalProvider = false
} = {}) {
    const mode = AI_MODE_VALUES.has(draft?.mode) ? draft.mode : "DEEPSEEK";
    if (!String(draft?.apiKey ?? "").trim() && configured !== true) {
        return "请输入 API Key。";
    }
    if (mode === "CUSTOM") {
        const baseUrl = String(draft?.baseUrl ?? "").trim();
        if (!baseUrl) {
            return "自定义模式需要 API Base URL。";
        }
        let parsed;
        try {
            parsed = new URL(baseUrl);
        } catch {
            return "自定义 API 必须使用 HTTPS。";
        }
        const localHost = new Set(["localhost", "127.0.0.1", "::1", "[::1]"])
            .has(parsed.hostname.toLowerCase());
        const permittedProtocol = parsed.protocol === "https:"
            || (allowLocalProvider && parsed.protocol === "http:" && localHost);
        if (!permittedProtocol) {
            return "自定义 API 必须使用 HTTPS。";
        }
        if (parsed.username || parsed.password || parsed.search || parsed.hash) {
            return "API Base URL 不能包含凭据、查询参数或片段。";
        }
        if (!String(draft?.model ?? "").trim()) {
            return "自定义模式需要 Model。";
        }
    }
    return "";
}
