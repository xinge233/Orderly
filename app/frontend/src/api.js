export class ApiError extends Error {
    /**
     * @param {string} message 错误消息
     * @param {{status?: number, payload?: unknown}} details 细节
     */
    constructor(message, { status = 0, payload = null } = {}) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.payload = payload;
    }
}

export class OrderlyApi {
    #fetch;
    #baseUrl;

    /**
     * @param {{fetchImpl?: typeof fetch, baseUrl?: string}} options 配置
     */
    constructor({
        fetchImpl = globalThis.fetch?.bind(globalThis),
        baseUrl = ""
    } = {}) {
        if (typeof fetchImpl !== "function") {
            throw new TypeError("当前环境不支持 fetch");
        }
        this.#fetch = fetchImpl;
        this.#baseUrl = String(baseUrl).replace(/\/$/, "");
    }

    /**
     * 请求 JSON API。
     *
     * @param {string} path 路径
     * @param {{method?: string, body?: unknown, signal?: AbortSignal}} options 选项
     * @returns {Promise<unknown>} 响应
     */
    async request(path, { method = "GET", body, signal } = {}) {
        let response;
        try {
            response = await this.#fetch(`${this.#baseUrl}${path}`, {
                method,
                signal,
                headers: {
                    Accept: "application/json",
                    ...(body === undefined ? {} : { "Content-Type": "application/json" })
                },
                ...(body === undefined ? {} : { body: JSON.stringify(body) })
            });
        } catch (error) {
            if (error?.name === "AbortError") {
                throw error;
            }
            throw new ApiError("无法连接本地服务，请确认宿主机已启动。", {
                payload: error
            });
        }

        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch {
                payload = text;
            }
        }

        if (!response.ok) {
            const message = payload && typeof payload === "object"
                ? payload.message ?? payload.error ?? `请求失败（${response.status}）`
                : String(payload || `请求失败（${response.status}）`);
            throw new ApiError(String(message), {
                status: response.status,
                payload
            });
        }
        return payload;
    }

    health(options) {
        return this.request("/api/health", options);
    }

    getModules(options) {
        return this.request("/api/modules", options);
    }

    getThemes(options) {
        return this.request("/api/themes", options);
    }

    getTheme(id, options) {
        return this.request(`/api/themes/${encodeURIComponent(id)}`, options);
    }

    getModuleUi(moduleId, options) {
        return this.request(`/api/modules/${encodeURIComponent(moduleId)}/ui/module.json`, options);
    }

    moduleAssetUrl(moduleId, asset) {
        return `${this.#baseUrl}/api/modules/${encodeURIComponent(moduleId)}/ui/${encodeURIComponent(asset)}`;
    }

    queryModule(moduleId, queryId, parameters = {}, options) {
        const search = new URLSearchParams();
        Object.entries(parameters).forEach(([key, value]) => {
            if (value != null && String(value) !== "") {
                search.set(key, String(value));
            }
        });
        const query = search.size ? `?${search.toString()}` : "";
        return this.request(
            `/api/modules/${encodeURIComponent(moduleId)}/query/${encodeURIComponent(queryId)}${query}`,
            options
        );
    }

    getRuns(options) {
        return this.request("/api/runs", options);
    }

    getEvents(options) {
        return this.request("/api/events", options);
    }

    draftPlan(goal, options = {}) {
        return this.request("/api/plans", {
            ...options,
            method: "POST",
            body: { goal }
        });
    }

    confirmPlan(planId, confirmation, options = {}) {
        return this.request(`/api/plans/${encodeURIComponent(planId)}/confirm`, {
            ...options,
            method: "POST",
            body: confirmation
        });
    }

    rollbackPlan(planId, options = {}) {
        return this.request(`/api/plans/${encodeURIComponent(planId)}/rollback`, {
            ...options,
            method: "POST",
            body: {}
        });
    }

    /**
     * 订阅执行事件。具名事件与默认 message 均兼容。
     *
     * @param {{onEvent: (event: object) => void, onError?: (error: Event) => void}} handlers 回调
     * @returns {{close: () => void, source: EventSource | null}} 订阅
     */
    streamEvents({ onEvent, onError }) {
        if (typeof globalThis.EventSource !== "function") {
            return { close() {}, source: null };
        }
        const source = new EventSource(`${this.#baseUrl}/api/events/stream`);
        const handle = (message) => {
            if (!message.data) {
                return;
            }
            try {
                onEvent(JSON.parse(message.data));
            } catch {
                // 非 JSON 心跳不进入审计时间线。
            }
        };
        source.addEventListener("message", handle);
        source.addEventListener("event", handle);
        source.addEventListener("execution", handle);
        if (onError) {
            source.addEventListener("error", onError);
        }
        return {
            source,
            close: () => source.close()
        };
    }
}
