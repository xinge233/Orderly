/**
 * 本地外观设置、主题目录与主题应用状态。
 *
 * 职责：保存主题、动效、AI 图标、通知偏好和总览卡片顺序；校验宿主计划开关；按启动目录
 * 校正主题选择，并以事务方式装载目录主题 CSS。
 *
 * 状态所有权：功能开关和模块侧栏/管理顺序属于宿主，不能写入本文件的 `SettingsStore`。
 * 后者只保存本地展示状态，并避免持久化凭据或业务事实。
 *
 * 副作用：`SettingsStore` 会读写注入的 Storage 并通知订阅者；`ThemeManager` 会请求主题
 * 详情、增删文档样式表并更新根节点与 meta。纯规范化函数不产生这些副作用。
 */
import { AI_ICON_OPTIONS, normalizeAiIcon } from "./icons.js";
import { reconcileModuleOrder } from "./modules.js";

export const MOTION_OPTIONS = Object.freeze([
    { value: "gentle", label: "灵动" },
    { value: "minimal", label: "克制" },
    { value: "none", label: "关闭" }
]);

export const AI_PANEL_WIDTH_LIMITS = Object.freeze({
    default: 460,
    minimum: 340,
    maximum: 720
});

const DEFAULTS = Object.freeze({
    theme: "",
    motion: "gentle",
    aiIcon: "spark",
    aiPanelWidth: AI_PANEL_WIDTH_LIMITS.default,
    notifications: true,
    moduleOrderIds: []
});

const MOTIONS = new Set(MOTION_OPTIONS.map((item) => item.value));
const THEME_ID = /^[a-z][a-z0-9-]{0,63}$/;
const THEME_STYLE_PATH = /^(?:[a-z0-9][a-z0-9.-]*\/)*[a-z0-9][a-z0-9.-]*\.css$/;
const THEME_AI_ICON_PATH = /^assets\/ai\/icons\/(spark|route|shield|modules)\.png$/;
const SUPPORTED_THEME_SCHEMA_VERSION = 1;
const REQUIRED_THEME_STYLES = Object.freeze(["tokens.css", "motion.css"]);

/**
 * 清洗 AI 面板宽度，使其保持在可读且不会吞没工作区的范围内。
 *
 * @param {unknown} value 原始宽度
 * @param {number} [fallback] 缺失或非法值时的默认宽度
 * @returns {number} 整数像素宽度
 */
export function normalizeAiPanelWidth(
    value,
    fallback = AI_PANEL_WIDTH_LIMITS.default
) {
    const normalizedFallback = Number.isFinite(Number(fallback))
        ? Number(fallback)
        : AI_PANEL_WIDTH_LIMITS.default;
    const parsed = value == null || value === "" ? Number.NaN : Number(value);
    const candidate = Number.isFinite(parsed) ? parsed : normalizedFallback;
    return Math.round(Math.min(
        AI_PANEL_WIDTH_LIMITS.maximum,
        Math.max(AI_PANEL_WIDTH_LIMITS.minimum, candidate)
    ));
}

/**
 * 读取宿主计划功能状态；缺失或损坏时按产品默认关闭处理。
 *
 * @param {unknown} payload /api/system/features 响应
 * @param {boolean} fallback 缺省值
 * @returns {boolean} 是否启用
 *
 * 只接受真正的布尔值；宿主响应破损时采用调用方提供的安全缺省值，不把字符串当开关。
 */
export function normalizePlanningFeature(payload, fallback = false) {
    return payload
        && typeof payload === "object"
        && typeof payload.planningEnabled === "boolean"
        ? payload.planningEnabled
        : fallback;
}

/**
 * 读取 AI 是否允许返回计划；缺失或损坏时按默认开启处理。
 *
 * @param {unknown} payload /api/system/features 响应
 * @param {boolean} fallback 缺省值
 * @returns {boolean} 是否允许 AI 返回计划
 *
 * 该设置与旧版计划审查开关相互独立；缺失时默认允许 AI 产出计划，但仍需用户确认执行。
 */
export function normalizeAiPlanFeature(payload, fallback = true) {
    return payload
        && typeof payload === "object"
        && typeof payload.aiPlanEnabled === "boolean"
        ? payload.aiPlanEnabled
        : fallback;
}

/**
 * 规范化宿主拥有的模块构建并发设置。
 *
 * @param {unknown} payload /api/system/build-settings 响应
 * @param {number} fallback 缺省并发数
 * @returns {{maxParallel: number, minimum: number, maximum: number}} 安全设置
 */
export function normalizeBuildSettings(payload, fallback = 2) {
    const source = payload && typeof payload === "object" && !Array.isArray(payload)
        ? payload
        : {};
    const minimum = 1;
    const maximum = 4;
    const fallbackValue = Math.min(maximum, Math.max(minimum, Number(fallback) || 2));
    const maxParallel = Number.isInteger(source.maxParallel)
        && source.maxParallel >= minimum
        && source.maxParallel <= maximum
        ? source.maxParallel
        : fallbackValue;
    return { maxParallel, minimum, maximum };
}

/**
 * 清洗本地保存的总览卡片顺序。
 *
 * @param {object} source 设置对象
 * @param {string} key 数组字段名
 * @returns {string[]} 去重后的非空 ID
 *
 * 异常与副作用：无；字段不是数组或包含非字符串项时按项丢弃。
 */
function normalizeModuleOrder(source, key) {
    if (!Array.isArray(source[key])) {
        return [];
    }
    return [...new Set(source[key]
        .filter((id) => typeof id === "string" && id.trim())
        .map((id) => id.trim()))];
}

/**
 * 清洗可能损坏或过期的本地设置。
 *
 * @param {unknown} input 原始值
 * @returns {object} 合法设置
 *
 * 输出集合刻意固定为本地展示字段：旧版可见性字段、计划开关和 AI 查询共享设置即使出现在
 * 输入中也会被丢弃，防止把宿主状态复制成第二套真相源。副作用：无。
 */
export function normalizeSettings(input) {
    const source = input && typeof input === "object" ? input : {};
    return {
        theme: typeof source.theme === "string" ? source.theme.trim() : DEFAULTS.theme,
        motion: MOTIONS.has(source.motion) ? source.motion : DEFAULTS.motion,
        aiIcon: normalizeAiIcon(source.aiIcon),
        aiPanelWidth: normalizeAiPanelWidth(source.aiPanelWidth),
        notifications: typeof source.notifications === "boolean"
            ? source.notifications
            : DEFAULTS.notifications,
        moduleOrderIds: normalizeModuleOrder(source, "moduleOrderIds")
    };
}

/**
 * 用服务端目录校正当前设置。主题被删除时只回退可用默认主题。
 *
 * @param {unknown} input 原始设置
 * @param {Array<object>} modules 模块契约
 * @param {Array<object>} themes 主题摘要
 * @returns {object} 已校正设置
 *
 * 主题失效时只回退可用默认主题；没有默认主题则清空选择并交给内置外观兜底。模块顺序
 * 只校正本地总览卡片，不触碰宿主拥有的侧栏或管理顺序。副作用：无。
 */
export function reconcileSettingsForCatalog(input, modules = [], themes = []) {
    const settings = normalizeSettings(input);
    return {
        ...settings,
        theme: resolveThemeSelection(settings.theme, themes),
        moduleOrderIds: reconcileModuleOrder(modules, settings.moduleOrderIds)
    };
}

/**
 * 解析仍有效的主题 id。
 *
 * @param {string} selected 已选主题
 * @param {Array<object>} themes 主题摘要
 * @returns {string} 主题 id 或空字符串
 *
 * 有效已选主题优先；否则只选择明确标记为 default 的可用主题。排序第一项不能隐式成为
 * 默认主题。异常与副作用：无。
 */
export function resolveThemeSelection(selected, themes = []) {
    const ordered = availableThemes(themes);
    if (ordered.some((theme) => theme.id === selected)) {
        return selected;
    }
    return resolveDefaultTheme(ordered);
}

/**
 * 解析唯一可用默认主题；不选择排序后的第一项。
 *
 * @param {Array<object>} themes 主题摘要
 * @returns {string} 默认主题 id 或空字符串
 *
 * 若宿主导入多个默认主题，只取排序后的第一个用于界面降级；重复默认主题应在宿主启动时
 * 被拒绝，不能依赖此函数修复目录。异常与副作用：无。
 */
export function resolveDefaultTheme(themes = []) {
    return availableThemes(themes).find((theme) => theme.default === true)?.id ?? "";
}

/**
 * 将主题管理器最终应用结果校正为设置值。
 *
 * @param {unknown} requested 请求的主题 id
 * @param {unknown} appliedTheme 实际应用的主题详情
 * @returns {{theme: string} | null} 需要持久化的局部设置
 *
 * 只有实际应用 id 与请求一致时才返回 `null`，否则把设置校正为真实主题或空字符串，避免
 * UI 声称正在使用一个加载失败的目录主题。异常与副作用：无。
 */
export function reconcileAppliedTheme(requested, appliedTheme) {
    const requestedId = typeof requested === "string" ? requested : "";
    const appliedId = appliedTheme
        && typeof appliedTheme === "object"
        && typeof appliedTheme.id === "string"
        && THEME_ID.test(appliedTheme.id)
        && appliedTheme.schemaVersion === SUPPORTED_THEME_SCHEMA_VERSION
        ? appliedTheme.id
        : "";
    return requestedId === appliedId ? null : { theme: appliedId };
}

/**
 * 从主题详情解析当前主题可用的 AI 图标 PNG 地址。
 *
 * @param {unknown} theme 已应用主题详情
 * @param {string} baseUrl 主题 API 根路径
 * @returns {Record<string, string>} AI 图标 ID 到资源 URL 的映射
 *
 * 只接受当前四个 AI 图标 ID、固定 `assets/ai/icons/<id>.png` 路径和主题 API 根路径。
 * 主题缺失、版本不支持、映射损坏或资源路径不匹配时返回空映射，由 AI Dock 使用通用
 * 兜底图形。异常与副作用：无。
 */
export function resolveThemeAiIconUrls(theme, baseUrl = "/api/themes") {
    if (!theme
            || typeof theme !== "object"
            || !THEME_ID.test(theme.id)
            || theme.schemaVersion !== SUPPORTED_THEME_SCHEMA_VERSION
            || !theme.aiIconAssets
            || typeof theme.aiIconAssets !== "object"
            || Array.isArray(theme.aiIconAssets)) {
        return {};
    }
    const apiBase = String(baseUrl ?? "").replace(/\/+$/, "");
    if (apiBase !== "/api/themes") {
        return {};
    }
    const encodedTheme = encodeURIComponent(theme.id);
    return Object.fromEntries(AI_ICON_OPTIONS.flatMap((option) => {
        const asset = theme.aiIconAssets[option.value];
        if (typeof asset !== "string") {
            return [];
        }
        const match = THEME_AI_ICON_PATH.exec(asset);
        if (!match || match[1] !== option.value) {
            return [];
        }
        const encodedAsset = asset
            .split("/")
            .map((segment) => encodeURIComponent(segment))
            .join("/");
        return [[option.value, `${apiBase}/${encodedTheme}/assets/${encodedAsset}`]];
    }));
}

/**
 * 规范化 /api/themes 启动快照。
 *
 * @param {unknown} payload API 响应
 * @returns {{items: Array<object>, issues: Array<object>, builtInFallbackActive: boolean}} 目录快照
 *
 * 只保留当前 schema 版本、合法 ID 的主题；问题项浅拷贝后保留给设置页诊断。宿主没有明确
 * 说明回退状态时，以“是否还存在可用默认主题”推导。异常与副作用：无。
 */
export function normalizeThemeCatalog(payload) {
    const source = payload && typeof payload === "object" && !Array.isArray(payload)
        ? payload
        : {};
    const items = availableThemes(Array.isArray(source.items) ? source.items : []);
    const issues = Array.isArray(source.issues)
        ? source.issues.filter((issue) => issue && typeof issue === "object").map((issue) => ({ ...issue }))
        : [];
    return {
        items,
        issues,
        builtInFallbackActive: typeof source.builtInFallbackActive === "boolean"
            ? source.builtInFallbackActive
            : !items.some((theme) => theme.default === true)
    };
}

/**
 * 浏览器本地展示设置的单一状态容器。
 *
 * 实例持有一个已清洗快照，更新时合并补丁、重新清洗并尽力写入 Storage，随后同步通知订阅者。
 * 存储不可用或内容损坏时保留安全的会话内状态，不阻断页面启动。
 *
 * 副作用：`update()`/`reset()` 写 Storage 并调用监听器；`getSnapshot()` 和 `subscribe()`
 * 不写入业务状态。快照是浅冻结对象，其中数组也已冻结。
 */
export class SettingsStore {
    #storage;
    #storageKey;
    #listeners = new Set();
    #value;

    /**
     * @param {{storage?: Storage, storageKey?: string}} options 配置
     */
    constructor({ storage = globalThis.localStorage, storageKey = "orderly.settings.v1" } = {}) {
        this.#storage = storage;
        this.#storageKey = storageKey;
        this.#value = this.#read();
    }

    /**
     * 返回当前设置的只读快照。
     *
     * @returns {Readonly<object>} 当前设置
     *
     * 每次调用都会复制顶层对象和顺序数组，防止调用方绕过 `update()` 修改内部状态。
     * 异常与副作用：无。
     */
    getSnapshot() {
        return Object.freeze({
            ...this.#value,
            moduleOrderIds: Object.freeze([...this.#value.moduleOrderIds])
        });
    }

    /**
     * 更新设置并持久化。
     *
     * @param {object} patch 局部设置
     * @returns {object} 新快照
     *
     * 未声明字段会在 `normalizeSettings()` 中被丢弃。Storage 写入失败被有意降级为仅当前
     * 会话生效，但内存状态和监听器仍会更新。异常：不向调用方抛出存储错误。
     * 副作用：更新内部状态、尝试写 Storage，并同步通知所有订阅者。
     */
    update(patch) {
        this.#value = normalizeSettings({ ...this.#value, ...patch });
        try {
            this.#storage?.setItem(this.#storageKey, JSON.stringify(this.#value));
        } catch {
            // 存储不可用时仍保留当前会话设置。
        }
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    /**
     * 恢复全部本地设置默认值。
     *
     * @returns {object} 默认设置快照
     *
     * Storage 删除失败不会阻止会话状态重置。副作用：替换内部状态、尝试删除持久化键并通知
     * 订阅者；异常：不向调用方抛出存储错误。
     */
    reset() {
        this.#value = normalizeSettings(DEFAULTS);
        try {
            this.#storage?.removeItem(this.#storageKey);
        } catch {
            // 忽略受限浏览器的存储异常。
        }
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    /**
     * 订阅设置更新。
     *
     * @param {(snapshot: Readonly<object>) => void} listener 监听器
     * @returns {() => boolean} 取消订阅函数
     *
     * 订阅不会立即推送当前值，调用方应先读取 `getSnapshot()`。异常与副作用：无，除维护
     * 内存中的监听器集合外；取消函数按 `Set.delete()` 的结果返回是否移除成功。
     */
    subscribe(listener) {
        this.#listeners.add(listener);
        return () => this.#listeners.delete(listener);
    }

    /**
     * 从 Storage 读取并清洗初始值。
     *
     * @returns {object} 可用的本地设置
     *
     * 缺失、JSON 损坏或 Storage 读取异常都会回退到默认值，避免损坏的本地数据阻止应用启动。
     * 异常与副作用：内部吞掉存储与解析错误，不修改 Storage。
     */
    #read() {
        try {
            const raw = this.#storage?.getItem(this.#storageKey);
            return normalizeSettings(raw ? JSON.parse(raw) : DEFAULTS);
        } catch {
            return normalizeSettings(DEFAULTS);
        }
    }
}

/**
 * 目录主题的事务式应用器。
 *
 * 每次 `apply()` 递增 generation，慢请求只有在仍是最新代次时才能提交；候选 CSS 全部加载
 * 成功后才切换根主题并移除旧样式，失败时保留当前主题。主题详情和资源路径只来自宿主目录；
 * 候选失败后先保留当前主题，首次加载无现成主题时再尝试目录默认主题，最后才回到内置外观。
 *
 * 副作用：`apply()` 会发起请求，`apply()` 与 `clear()` 都会修改文档 `<head>`、根节点
 * dataset 与 meta；缓存和代次状态保存在实例内。
 */
export class ThemeManager {
    #document;
    #fetch;
    #baseUrl;
    #cache = new Map();
    #catalog = normalizeThemeCatalog({});
    #generation = 0;
    #active = null;
    #current = null;

    /**
     * @param {{documentRef?: Document, fetchImpl?: typeof fetch, baseUrl?: string}} options 配置
     */
    constructor({
        documentRef = globalThis.document,
        fetchImpl = globalThis.fetch?.bind(globalThis),
        baseUrl = "/api/themes"
    } = {}) {
        this.#document = documentRef;
        this.#fetch = fetchImpl;
        this.#baseUrl = String(baseUrl).replace(/\/$/, "");
    }

    /**
     * 更新当前启动目录快照。
     *
     * @param {unknown} payload /api/themes 响应
     * @returns {{items: Array<object>, issues: Array<object>, builtInFallbackActive: boolean}} 目录快照
     *
     * 仅替换实例中的目录事实，不立即应用或卸载样式；应用动作始终由 `apply()` 显式触发。
     * 异常与副作用：不抛异常，也不修改文档。
     */
    setCatalog(payload) {
        this.#catalog = normalizeThemeCatalog(payload);
        return this.#catalog;
    }

    /**
     * 事务化加载目录主题，成功后原子替换当前样式表。
     *
     * @param {string} name 主题 id
     * @returns {Promise<object | null>} 主题定义
     *
     * 返回的是实际应用的主题详情，可能因请求主题不可用而变为默认主题；没有可用目录主题或
     * 候选加载失败且无法回退时返回 `null`。过期请求也返回 `null`，但不会清理更新代次已提交
     * 的样式。预期网络、解析和样式加载失败均被隔离，不向调用方抛出。
     *
     * 副作用：可能发起主题详情和 CSS 请求，成功后原子切换文档样式；失败时清理候选链接。
     */
    async apply(name) {
        const generation = ++this.#generation;
        const safeName = typeof name === "string" && THEME_ID.test(name) ? name : "";
        const requestedAvailable = this.#catalog.items.some((theme) => theme.id === safeName);
        const defaultId = resolveDefaultTheme(this.#catalog.items);
        const candidateId = requestedAvailable ? safeName : defaultId;
        if (!candidateId) {
            if (generation === this.#generation) {
                this.#removeLinks(this.#active?.links);
                this.#active = null;
                this.#current = null;
                this.#clearDocumentTheme();
            }
            return null;
        }

        const applied = await this.#tryApplyCandidate(candidateId, generation);
        if (applied === STALE_REQUEST) {
            return null;
        }
        if (applied) {
            return applied;
        }
        if (generation !== this.#generation) {
            return null;
        }
        if (this.#active?.theme) {
            return this.#current;
        }

        if (defaultId && defaultId !== candidateId) {
            const fallback = await this.#tryApplyCandidate(defaultId, generation);
            if (fallback === STALE_REQUEST) {
                return null;
            }
            if (fallback) {
                return fallback;
            }
        }

        if (generation === this.#generation) {
            this.#clearDocumentTheme();
            this.#current = null;
        }
        return null;
    }

    /**
     * 立即卸载目录主题并回退到内置安全外观。
     *
     * @returns {void}
     *
     * 副作用：递增代次以作废未完成请求，移除当前主题链接，清除根主题标记和颜色 meta；
     * 异常：不主动抛出异常。
     */
    clear() {
        this.#generation += 1;
        this.#removeLinks(this.#active?.links);
        this.#active = null;
        this.#clearDocumentTheme();
        this.#current = null;
    }

    get current() {
        return this.#current;
    }

    /**
     * 尝试加载、校验并提交一个主题候选。
     *
     * @param {string} id 候选主题 ID
     * @param {number} generation 发起时的请求代次
     * @returns {Promise<object | symbol | null>} 主题详情、过期标记或失败时的 null
     *
     * 详情缓存命中时仍重新校验 schema 和样式清单；任何失败都会删除该 id 的缓存并返回 null，
     * 让外层决定是否回退。只有代次仍匹配且全部 CSS 已加载时才提交 DOM 变更。
     *
     * 副作用：可能发请求、更新缓存、创建并移除 link，成功时提交根主题与 meta。
     */
    async #tryApplyCandidate(id, generation) {
        let theme = this.#cache.get(id);
        try {
            if (!theme) {
                const response = await this.#fetch(`${this.#baseUrl}/${encodeURIComponent(id)}`);
                if (!response.ok) {
                    throw new Error(`主题加载失败：${response.status}`);
                }
                theme = await response.json();
                this.#cache.set(id, theme);
            }
            this.#validateTheme(id, theme);
            const links = await this.#loadStyles(id, theme.styles, generation);
            if (links === STALE_REQUEST || generation !== this.#generation) {
                this.#removeLinks(links);
                return STALE_REQUEST;
            }

            const root = this.#document?.documentElement;
            if (root) {
                root.dataset.theme = id;
            }
            this.#applyDocumentMeta(theme);
            links.forEach((link) => {
                link.media = "all";
            });
            this.#removeLinks(this.#active?.links);
            this.#active = { theme, links };
            this.#current = theme;
            return theme;
        } catch {
            this.#cache.delete(id);
            return null;
        }
    }

    /**
     * 校验主题详情与必需样式清单。
     *
     * @param {string} id 请求主题 ID
     * @param {unknown} theme 主题详情
     * @returns {void}
     * @throws {TypeError} schema 版本、主题 ID、styles 数组或资源路径不合法时抛出
     *
     * 样式路径必须为相对 CSS 文件，且至少包含 `tokens.css` 与 `motion.css`；该函数不接受
     * 主题脚本或任意 HTML。副作用：无。
     */
    #validateTheme(id, theme) {
        if (!theme || typeof theme !== "object"
            || theme.schemaVersion !== SUPPORTED_THEME_SCHEMA_VERSION
            || theme.id !== id
            || !Array.isArray(theme.styles)) {
            throw new TypeError("主题详情不符合 schemaVersion 1");
        }
        const styles = theme.styles.filter((style) =>
            typeof style === "string" && THEME_STYLE_PATH.test(style));
        if (styles.length !== theme.styles.length
            || REQUIRED_THEME_STYLES.some((required) => !styles.includes(required))) {
            throw new TypeError("主题样式清单非法");
        }
    }

    /**
     * 以隐藏状态并行加载一个主题的全部样式表。
     *
     * @param {string} id 主题 ID
     * @param {string[]} styles 已校验的样式路径
     * @param {number} generation 请求代次
     * @returns {Promise<HTMLLinkElement[] | symbol>} 已加载链接或过期标记
     * @throws {TypeError} 当前文档缺少 head 或创建元素能力时抛出
     * @throws {Error} 任一候选样式加载失败时抛出
     *
     * 链接先以 `media="not all"` 插入，全部 load 后才由调用方启用，避免只加载一部分变量时
     * 页面闪到半成品主题。失败或已过期会移除本次创建的全部链接。
     * 副作用：修改文档 head 并等待网络资源。
     */
    async #loadStyles(id, styles, generation) {
        const head = this.#document?.head;
        if (!head || typeof this.#document?.createElement !== "function") {
            throw new TypeError("当前文档无法加载主题样式");
        }
        const links = [];
        try {
            const waits = styles.map((style) => {
                const link = this.#document.createElement("link");
                link.rel = "stylesheet";
                link.media = "not all";
                link.href = `${this.#baseUrl}/${encodeURIComponent(id)}/assets/${style
                    .split("/")
                    .map((segment) => encodeURIComponent(segment))
                    .join("/")}`;
                link.dataset.orderlyThemeStyle = id;
                links.push(link);
                const loaded = new Promise((resolve, reject) => {
                    link.addEventListener("load", resolve, { once: true });
                    link.addEventListener("error", () => reject(
                        new Error(`主题样式加载失败：${link.href}`)
                    ), { once: true });
                });
                head.append(link);
                return loaded;
            });
            await Promise.all(waits);
            if (generation !== this.#generation) {
                this.#removeLinks(links);
                return STALE_REQUEST;
            }
            return links;
        } catch (error) {
            this.#removeLinks(links);
            throw error;
        }
    }

    /**
     * 移除一组主题样式链接。
     *
     * @param {Array<HTMLLinkElement>} [links=[]] 待移除链接
     * @returns {void}
     *
     * 优先使用元素的 `remove()`，兼容测试替身时再调用父节点。副作用：修改文档 DOM；
     * 非数组输入会被忽略。函数不主动抛错，但异常 DOM 节点自身的移除方法仍可能传播错误。
     */
    #removeLinks(links = []) {
        for (const link of Array.isArray(links) ? links : []) {
            if (typeof link.remove === "function") {
                link.remove();
            } else {
                link.parentNode?.removeChild?.(link);
            }
        }
    }

    /**
     * 将主题声明的浏览器颜色同步到文档 meta。
     *
     * @param {object} theme 已校验主题详情
     * @returns {void}
     *
     * 仅更新已存在的 meta，不创建新节点；缺失或非字符串颜色时使用安全的浏览器默认值。
     * 副作用：修改 color-scheme 与主题色 meta。
     */
    #applyDocumentMeta(theme) {
        const schemeMeta = this.#document?.querySelector?.('meta[name="color-scheme"]');
        if (schemeMeta) {
            schemeMeta.content = typeof theme.colorScheme === "string" && theme.colorScheme
                ? theme.colorScheme
                : "light dark";
        }
        const browserMeta = this.#document?.querySelector?.('meta[id="theme-color-meta"]');
        if (browserMeta) {
            browserMeta.content = typeof theme.browserColor === "string" && theme.browserColor
                ? theme.browserColor
                : "Canvas";
        }
    }

    /**
     * 清除目录主题留下的根标记与浏览器颜色 meta。
     *
     * @returns {void}
     *
     * 移除 `data-theme` 后由内置 CSS 接管外观；两个 meta 恢复浏览器默认值。
     * 副作用：修改文档根节点和 meta；异常：不主动抛出异常。
     */
    #clearDocumentTheme() {
        const root = this.#document?.documentElement;
        if (root) {
            delete root.dataset.theme;
        }
        const meta = this.#document?.querySelector?.('meta[name="color-scheme"]');
        if (meta) {
            meta.content = "light dark";
        }
        const browserMeta = this.#document?.querySelector?.('meta[id="theme-color-meta"]');
        if (browserMeta) {
            browserMeta.content = "Canvas";
        }
    }
}

const STALE_REQUEST = Symbol("stale-theme-request");

/**
 * 过滤、补齐并排序可用主题摘要。
 *
 * @param {Array<unknown>} themes 原始主题摘要
 * @returns {Array<object>} 可排序的主题副本
 *
 * 只接受 schemaVersion 1 和合法小写主题 ID；order 非有限数字时按 0 处理，default 仅严格
 * 等于 true 时成立。异常与副作用：无，不修改输入。
 */
function availableThemes(themes) {
    return [...themes]
        .filter((theme) => theme
            && typeof theme.id === "string"
            && THEME_ID.test(theme.id)
            && theme.schemaVersion === SUPPORTED_THEME_SCHEMA_VERSION)
        .map((theme) => ({
            ...theme,
            order: Number.isFinite(Number(theme.order)) ? Number(theme.order) : 0,
            default: theme.default === true
        }))
        .sort((left, right) => left.order - right.order || left.id.localeCompare(right.id));
}
