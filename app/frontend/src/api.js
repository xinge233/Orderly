/**
 * Orderly 宿主的 HTTP 与 SSE 客户端。
 *
 * 职责：统一 JSON 请求、multipart 上传、服务端错误归一化和执行事件订阅，并为各资源端点
 * 提供薄封装。该模块不解释授权策略，也不替代宿主校验；它只把网络层事实转换为调用方可
 * 稳定处理的 `ApiError` 或响应数据。
 *
 * 副作用：除 URL 构造函数外，公开方法通常会发起网络请求；`streamEvents()` 还会创建
 * `EventSource`，调用方必须保存并关闭返回的订阅。
 */

/**
 * 携带 HTTP 状态和原始响应体的客户端错误。
 *
 * 网络层没有收到 HTTP 响应时 `status` 为 0；`payload` 保留服务端原始 JSON、文本或底层
 * 异常，便于上层展示或诊断，但调用方不应把其中的文本当作可信 HTML。
 */
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

/**
 * 面向 Orderly 宿主 API 的轻量客户端。
 *
 * 构造函数可注入 `fetch` 和 Base URL 以支持测试或同源部署。实例方法不缓存业务数据，
 * 因此多次调用会反映宿主当前状态；需要一致快照的调用方应自行组合请求并处理竞态。
 */
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
     * @param {{method?: string, body?: unknown, signal?: AbortSignal, headers?: Record<string, string>}} options 选项
     * @returns {Promise<unknown>} 响应
     * @throws {ApiError | Error} 网络失败或非 2xx 时抛出 `ApiError`；主动取消时保留原始 AbortError
     *
     * 响应体先按文本读取：能解析为 JSON 时返回对象，否则保留文本，避免吞掉宿主的非标准错误页。
     * 网络异常统一包装为可展示的本地服务提示，但 AbortError 原样上抛，因为取消不是连接故障。
     */
    async request(path, { method = "GET", body, signal, headers = {} } = {}) {
        let response;
        try {
            response = await this.#fetch(`${this.#baseUrl}${path}`, {
                cache: "no-store",
                method,
                signal,
                headers: {
                    Accept: "application/json",
                    ...headers,
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
            // 优先使用宿主结构化错误；没有可用消息时仍附带 HTTP 状态，避免空白错误提示。
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

    getSystemModules(options) {
        return this.request("/api/system-modules", options);
    }

    getModuleBuilds(options) {
        return this.request("/api/system/module-builds", options);
    }

    prioritizeModuleBuild(sourceName, options = {}) {
        return this.request("/api/system/module-builds/prioritize", {
            ...options,
            method: "POST",
            body: { sourceName }
        });
    }

    shutdownHost(options = {}) {
        return this.request("/api/system/shutdown", {
            ...options,
            method: "POST",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "shutdown"
            }
        });
    }

    getModulePreferences(options) {
        return this.request("/api/system/module-preferences", options);
    }

    getModuleInterfaceState(options) {
        return this.request("/api/system/module-interfaces", options);
    }

    setModuleEnabled(moduleId, enabled, options = {}) {
        return this.request(`/api/modules/${encodeURIComponent(moduleId)}/activation`, {
            ...options,
            method: "PATCH",
            body: { enabled }
        });
    }

    saveSidebarModuleOrder(moduleIds, options = {}) {
        return this.request("/api/system/module-preferences/sidebar-order", {
            ...options,
            method: "PUT",
            body: { moduleIds }
        });
    }

    saveManagementModuleOrder(moduleIds, options = {}) {
        return this.request("/api/system/module-preferences/management-order", {
            ...options,
            method: "PUT",
            body: { moduleIds }
        });
    }

    saveModuleSettings(moduleId, values, options = {}) {
        return this.request(`/api/modules/${encodeURIComponent(moduleId)}/settings`, {
            ...options,
            method: "PUT",
            body: values
        });
    }

    resetModuleSettings(moduleId, options = {}) {
        return this.request(`/api/modules/${encodeURIComponent(moduleId)}/settings/reset`, {
            ...options,
            method: "POST"
        });
    }

    setInterfaceAiCallable(moduleId, kind, interfaceId, aiCallable, options = {}) {
        return this.request(
            `/api/modules/${encodeURIComponent(moduleId)}/interfaces/${encodeURIComponent(kind)}`
                + `/${encodeURIComponent(interfaceId)}/ai-callable`,
            {
                ...options,
                method: "PATCH",
                body: { aiCallable }
            }
        );
    }

    refreshSystemModules(options = {}) {
        return this.request("/api/system/modules/refresh", {
            ...options,
            method: "POST"
        });
    }

    openModuleFolder(options = {}) {
        return this.request("/api/system/module-folder/open", {
            ...options,
            method: "POST",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "open-module-folder"
            }
        });
    }

    getModuleCatalog(options) {
        return this.request("/api/system/module-catalog", options);
    }

    installModule(moduleId, options = {}) {
        return this.request("/api/system/module-catalog/install", {
            ...options,
            method: "POST",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "install-module"
            },
            body: { moduleId }
        });
    }

    getModuleInstallStatus(jobId, options) {
        return this.request(
            `/api/system/module-catalog/install/${encodeURIComponent(jobId)}`,
            options
        );
    }

    getModuleRemovals(options) {
        return this.request("/api/system/module-removals", options);
    }

    removeModule(moduleId, options = {}) {
        return this.request("/api/system/module-removals", {
            ...options,
            method: "POST",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "remove-module"
            },
            body: { moduleId }
        });
    }

    undoModuleRemoval(moduleId, options = {}) {
        return this.request("/api/system/module-removals/undo", {
            ...options,
            method: "POST",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "undo-module-removal"
            },
            body: { moduleId }
        });
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
        const encoded = String(asset ?? "")
            .split("/")
            .filter(Boolean)
            .map((segment) => encodeURIComponent(segment))
            .join("/");
        return `${this.#baseUrl}/api/modules/${encodeURIComponent(moduleId)}/ui/${encoded}`;
    }

    moduleDataUrl(moduleId, relativePath) {
        // 逐段编码而不是编码整个路径，既保留数据目录的层级分隔符，又消除各段中的保留字符。
        const encoded = String(relativePath ?? "")
            .split("/")
            .filter(Boolean)
            .map((segment) => encodeURIComponent(segment))
            .join("/");
        return `${this.#baseUrl}/api/modules/${encodeURIComponent(moduleId)}/data/${encoded}`;
    }

    /**
     * 以 multipart/form-data 上传文件并执行模块动作。
     *
     * @param {string} moduleId 模块 ID
     * @param {string} actionId 动作 ID
     * @param {unknown} argumentsValue 动作参数
     * @param {Blob | File} file 上传文件
     * @param {boolean} [confirmed=true] 是否已由界面取得用户确认
     * @param {{signal?: AbortSignal}} [options] 请求选项
     * @returns {Promise<unknown>} 宿主响应
     * @throws {ApiError | Error} 网络失败或非 2xx 时抛出；主动取消保留 AbortError
     *
     * 副作用：读取 `file` 并发送 multipart 请求。请求体刻意不设置 `Content-Type`，让浏览器
     * 生成包含 boundary 的正确头；确认标志只是提交事实，最终风险判定仍由宿主执行。
     */
    async executeModuleActionWithFile(
        moduleId,
        actionId,
        argumentsValue,
        file,
        confirmed = true,
        options = {}
    ) {
        const body = new FormData();
        body.append("request", new Blob([JSON.stringify({
            confirmed: confirmed === true,
            arguments: argumentsValue && typeof argumentsValue === "object"
                ? argumentsValue
                : {}
        })], { type: "application/json" }));
        body.append("file", file);
        let response;
        try {
            response = await this.#fetch(
                `${this.#baseUrl}/api/modules/${encodeURIComponent(moduleId)}/actions/${encodeURIComponent(actionId)}/upload`,
                {
                method: "POST",
                signal: options.signal,
                headers: {
                    Accept: "application/json"
                },
                body
            }
            );
        } catch (error) {
            if (error?.name === "AbortError") {
                throw error;
            }
            throw new ApiError("模块动作上传失败，请确认本地服务仍在运行。", {
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
            // 上传与普通 JSON 请求采用同一错误语义，调用方无需区分传输形态即可展示消息。
            const message = payload && typeof payload === "object"
                ? payload.message ?? payload.error ?? `上传失败（${response.status}）`
                : String(payload || `上传失败（${response.status}）`);
            throw new ApiError(String(message), {
                status: response.status,
                payload
            });
        }
        return payload;
    }

    /**
     * 执行普通模块动作。
     *
     * @param {string} moduleId 模块 ID
     * @param {string} actionId 动作 ID
     * @param {object} [argumentsValue={}] 动作参数
     * @param {boolean} [confirmed=true] 是否已由界面取得用户确认
     * @param {object} [options={}] 请求选项
     * @returns {Promise<unknown>} 宿主响应
     * @throws {ApiError | Error} 请求被拒绝、网络失败或主动取消时抛出
     *
     * 原始值、函数和空值会收敛为空对象；数组仍是 JavaScript 对象，因此原样提交给宿主
     * 校验。该方法不自行确认风险，宿主仍会复核确认状态、参数契约、快照与审计要求。
     */
    executeModuleAction(moduleId, actionId, argumentsValue = {}, confirmed = true, options = {}) {
        return this.request(
            `/api/modules/${encodeURIComponent(moduleId)}/actions/${encodeURIComponent(actionId)}`,
            {
                ...options,
                method: "POST",
                body: {
                    confirmed: confirmed === true,
                    arguments: argumentsValue && typeof argumentsValue === "object"
                        ? argumentsValue
                        : {}
                }
            }
        );
    }

    /**
     * 调用模块只读 Query。
     *
     * @param {string} moduleId 模块 ID
     * @param {string} queryId 查询 ID
     * @param {object} [parameters={}] 查询参数
     * @param {object} [options] 请求选项
     * @returns {Promise<unknown>} 查询结果
     * @throws {ApiError | Error} 请求被拒绝、网络失败或主动取消时抛出
     *
     * 空值与空字符串参数被省略，防止可选过滤条件被序列化为 `"null"` 或 `""`。参数编码
     * 只表达查询意图，不构成只读授权；模块启用状态和 Query 契约仍由宿主校验。
     */
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

    getExecutions(options) {
        return this.request("/api/executions", options);
    }

    rollbackExecution(executionId, options = {}) {
        return this.request(`/api/executions/${encodeURIComponent(executionId)}/rollback`, {
            ...options,
            method: "POST",
            body: {}
        });
    }

    getEvents(options) {
        return this.request("/api/events", options);
    }

    getSystemFeatures(options) {
        return this.request("/api/system/features", options);
    }

    getBuildSettings(options) {
        return this.request("/api/system/build-settings", options);
    }

    setBuildConcurrency(maxParallel, options = {}) {
        return this.request("/api/system/build-settings", {
            ...options,
            method: "PUT",
            headers: {
                ...options.headers,
                "X-Orderly-Action": "update-build-settings"
            },
            body: { maxParallel }
        });
    }

    getAiSettings(options) {
        return this.request("/api/ai/settings", options);
    }

    saveAiSettings(settings, options = {}) {
        return this.request("/api/ai/settings", {
            ...options,
            method: "PUT",
            body: settings
        });
    }

    clearAiSettings(options = {}) {
        return this.request("/api/ai/settings", {
            ...options,
            method: "DELETE"
        });
    }

    testAiSettings(options = {}) {
        return this.request("/api/ai/settings/test", {
            ...options,
            method: "POST",
            body: {}
        });
    }

    interpretAiTurn(payload, options = {}) {
        return this.request("/api/ai/turns", {
            ...options,
            method: "POST",
            body: payload
        });
    }

    confirmAiPlan(planId, options = {}) {
        return this.request(`/api/ai/plans/${encodeURIComponent(planId)}/confirm`, {
            ...options,
            method: "POST",
            body: {}
        });
    }

    setPlanningEnabled(enabled, options = {}) {
        return this.request("/api/system/features/planning", {
            ...options,
            method: "PUT",
            body: { enabled }
        });
    }

    setAiPlanEnabled(enabled, options = {}) {
        return this.request("/api/system/features/ai-plan", {
            ...options,
            method: "PUT",
            body: { enabled }
        });
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
     *
     * 副作用：创建长连接并持续调用 `onEvent`；调用方必须在页面卸载或重新连接前调用
     * `close()`。事件 JSON 或 `onEvent` 回调抛出的异常都会被当前处理块忽略；非 JSON
     * 心跳不会污染审计时间线，但回调副作用失败也不会由本方法报告。
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
