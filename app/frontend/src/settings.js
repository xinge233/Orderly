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
 * 用服务端目录校正当前设置。主题被删除时优先回退 default，再回退第一项。
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
    const ordered = [...themes]
        .filter((theme) => typeof theme?.id === "string" && THEME_ID.test(theme.id))
        .sort((left, right) => Number(left.order ?? 0) - Number(right.order ?? 0)
            || left.id.localeCompare(right.id));
    if (ordered.some((theme) => theme.id === selected)) {
        return selected;
    }
    return ordered.find((theme) => theme.default === true)?.id ?? ordered[0]?.id ?? "";
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
    #appliedTokens = new Set();
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
     * 加载主题令牌并应用到根元素。
     *
     * @param {string} name 主题 id
     * @returns {Promise<object | null>} 主题定义
     */
    async apply(name) {
        const safeName = typeof name === "string" && THEME_ID.test(name) ? name : "";
        this.#clearAppliedTokens();
        if (!safeName) {
            this.#clearDocumentTheme();
            this.#current = null;
            return null;
        }

        let theme = this.#cache.get(safeName);
        if (!theme) {
            try {
                const response = await this.#fetch(`${this.#baseUrl}/${encodeURIComponent(safeName)}`);
                if (!response.ok) {
                    throw new Error(`主题加载失败：${response.status}`);
                }
                theme = await response.json();
                this.#cache.set(safeName, theme);
            } catch {
                this.#clearDocumentTheme();
                this.#current = null;
                return null;
            }
        }

        const root = this.#document?.documentElement;
        if (!root) {
            this.#current = theme;
            return theme;
        }

        const applied = new Set();
        Object.entries(theme?.tokens ?? {}).forEach(([key, value]) => {
            if (/^--[a-z0-9-]+$/.test(key) && typeof value === "string") {
                root.style.setProperty(key, value);
                applied.add(key);
            }
        });
        this.#appliedTokens = applied;
        root.dataset.theme = safeName;
        const meta = this.#document.querySelector?.('meta[name="color-scheme"]');
        if (meta && theme?.colorScheme) {
            meta.content = theme.colorScheme;
        }
        const browserMeta = this.#document.querySelector?.('meta[id="theme-color-meta"]');
        if (browserMeta && theme?.browserColor) {
            browserMeta.content = theme.browserColor;
        }
        this.#current = theme;
        return theme;
    }

    clear() {
        this.#clearAppliedTokens();
        this.#clearDocumentTheme();
        this.#current = null;
    }

    #clearAppliedTokens() {
        const root = this.#document?.documentElement;
        if (!root) {
            this.#appliedTokens.clear();
            return;
        }
        this.#appliedTokens.forEach((key) => root.style.removeProperty(key));
        this.#appliedTokens.clear();
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
