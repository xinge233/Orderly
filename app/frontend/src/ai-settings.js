/**
 * AI 设置的前端视图模型。
 *
 * 职责：把宿主返回的设置快照收敛为固定字段，并在提交前校验用户临时输入的连接参数。
 * 边界：API Key 只作为一次提交草稿存在，既不会进入清洗结果，也不会由前端持久化；
 * 真正的密钥保存、模型请求和删除直执策略均由宿主与 Python AI 服务负责。
 *
 * 副作用：本文件中的函数均为纯函数，不读取浏览器存储、不发起请求，也不修改宿主状态。
 */
export const AI_MODES = Object.freeze([
    { value: "DEEPSEEK", label: "DeepSeek" },
    { value: "CUSTOM", label: "自定义" }
]);

const AI_MODE_VALUES = new Set(AI_MODES.map((item) => item.value));

/**
 * 清洗宿主返回的 AI 设置；响应永远不会包含 API Key。
 *
 * @param {unknown} payload /api/ai/settings 响应
 * @returns {{mode: string, baseUrl: string, model: string, configured: boolean, allowDeleteDirect: boolean, allowLocalProvider: boolean}} 设置
 *
 * 缺失字段采用保守默认值，布尔开关仅在宿主明确返回 `true` 时开启。这里不复制
 * `apiKey`，避免页面状态或后续渲染意外持有短暂凭据。
 */
export function normalizeAiSettings(payload) {
    const source = payload && typeof payload === "object" ? payload : {};
    return {
        mode: AI_MODE_VALUES.has(source.mode) ? source.mode : "DEEPSEEK",
        baseUrl: typeof source.baseUrl === "string" ? source.baseUrl : "",
        model: typeof source.model === "string" ? source.model : "",
        configured: source.configured === true,
        allowDeleteDirect: source.allowDeleteDirect === true,
        allowLocalProvider: source.allowLocalProvider === true
    };
}

/**
 * 校验临时 AI 设置草稿。
 *
 * @param {{mode: string, apiKey: string, baseUrl: string, model: string}} draft 草稿
 * @param {{configured?: boolean, allowLocalProvider?: boolean}} options 当前宿主状态
 * @returns {string} 错误消息；合法时返回空字符串
 *
 * 已配置时允许用户留空 API Key，以继续使用 Python 服务内存中的现有密钥。自定义端点
 * 默认强制 HTTPS；只有宿主明确开放本地提供方时，才接受回环地址上的 HTTP。此处只做
 * 提交前的快速反馈，宿主仍必须执行最终校验。
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
