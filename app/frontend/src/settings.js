import { reconcileModuleOrder } from "./modules.js";

export const MOTION_OPTIONS = Object.freeze([
    { value: "gentle", label: "灵动" },
    { value: "minimal", label: "克制" },
    { value: "none", label: "关闭" }
]);

const DEFAULTS = Object.freeze({
    theme: "",
    motion: "gentle",
    notifications: true,
    hiddenModuleIds: [],
    moduleOrderIds: [],
    sidebarModuleOrderIds: []
});

const MOTIONS = new Set(MOTION_OPTIONS.map((item) => item.value));
const THEME_ID = /^[a-z][a-z0-9-]{0,63}$/;
const THEME_STYLE_PATH = /^(?:[a-z0-9][a-z0-9.-]*\/)*[a-z0-9][a-z0-9.-]*\.css$/;
const SUPPORTED_THEME_SCHEMA_VERSION = 1;
const REQUIRED_THEME_STYLES = Object.freeze(["tokens.css", "motion.css"]);

function normalizeHiddenModules(source) {
    if (Array.isArray(source.hiddenModuleIds)) {
        return [...new Set(source.hiddenModuleIds
            .filter((id) => typeof id === "string" && id.trim())
            .map((id) => id.trim()))];
    }
    return [];
}

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
 */
export function normalizeSettings(input) {
    const source = input && typeof input === "object" ? input : {};
    return {
        theme: typeof source.theme === "string" ? source.theme.trim() : DEFAULTS.theme,
        motion: MOTIONS.has(source.motion) ? source.motion : DEFAULTS.motion,
        notifications: typeof source.notifications === "boolean"
            ? source.notifications
            : DEFAULTS.notifications,
        hiddenModuleIds: normalizeHiddenModules(source),
        moduleOrderIds: normalizeModuleOrder(source, "moduleOrderIds"),
        sidebarModuleOrderIds: normalizeModuleOrder(source, "sidebarModuleOrderIds")
    };
}

/**
 * 用服务端目录校正当前设置。主题被删除时只回退可用默认主题。
 *
 * @param {unknown} input 原始设置
 * @param {Array<object>} modules 模块契约
 * @param {Array<object>} themes 主题摘要
 * @returns {object} 已校正设置
 */
export function reconcileSettingsForCatalog(input, modules = [], themes = []) {
    const settings = normalizeSettings(input);
    const moduleIds = new Set(modules
        .map((module) => module?.moduleId)
        .filter((id) => typeof id === "string"));
    return {
        ...settings,
        theme: resolveThemeSelection(settings.theme, themes),
        hiddenModuleIds: settings.hiddenModuleIds.filter((id) => moduleIds.has(id)),
        moduleOrderIds: reconcileModuleOrder(modules, settings.moduleOrderIds),
        sidebarModuleOrderIds: reconcileModuleOrder(modules, settings.sidebarModuleOrderIds)
    };
}

/**
 * 解析仍有效的主题 id。
 *
 * @param {string} selected 已选主题
 * @param {Array<object>} themes 主题摘要
 * @returns {string} 主题 id 或空字符串
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
 * 规范化 /api/themes 启动快照。
 *
 * @param {unknown} payload API 响应
 * @returns {{items: Array<object>, issues: Array<object>, builtInFallbackActive: boolean}} 目录快照
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
 * 返回模块是否被用户隐藏。
 *
 * @param {object} settings 设置
 * @param {string} moduleId 模块 id
 * @returns {boolean} 是否隐藏
 */
export function isModuleHidden(settings, moduleId) {
    return normalizeSettings(settings).hiddenModuleIds.includes(moduleId);
}

/**
 * 更新单个模块可见性。
 *
 * @param {object} settings 设置
 * @param {string} moduleId 模块 id
 * @param {boolean} hidden 是否隐藏
 * @returns {object} 新设置
 */
export function setModuleHidden(settings, moduleId, hidden) {
    const current = normalizeSettings(settings);
    const hiddenIds = new Set(current.hiddenModuleIds);
    if (hidden) {
        hiddenIds.add(moduleId);
    } else {
        hiddenIds.delete(moduleId);
    }
    return {
        ...current,
        hiddenModuleIds: [...hiddenIds]
    };
}

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

    getSnapshot() {
        return Object.freeze({
            ...this.#value,
            hiddenModuleIds: Object.freeze([...this.#value.hiddenModuleIds]),
            moduleOrderIds: Object.freeze([...this.#value.moduleOrderIds]),
            sidebarModuleOrderIds: Object.freeze([...this.#value.sidebarModuleOrderIds])
        });
    }

    /**
     * 更新设置并持久化。
     *
     * @param {object} patch 局部设置
     * @returns {object} 新快照
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

    subscribe(listener) {
        this.#listeners.add(listener);
        return () => this.#listeners.delete(listener);
    }

    #read() {
        try {
            const raw = this.#storage?.getItem(this.#storageKey);
            return normalizeSettings(raw ? JSON.parse(raw) : DEFAULTS);
        } catch {
            return normalizeSettings(DEFAULTS);
        }
    }
}

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

    #removeLinks(links = []) {
        for (const link of Array.isArray(links) ? links : []) {
            if (typeof link.remove === "function") {
                link.remove();
            } else {
                link.parentNode?.removeChild?.(link);
            }
        }
    }

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
